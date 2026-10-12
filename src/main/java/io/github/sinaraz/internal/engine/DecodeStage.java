package io.github.sinaraz.internal.engine;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderProvider;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.Track;

/**
 * Owns the decoder and reads ahead into a bounded queue of PCM chunks.
 *
 * <p>The decoder is used by one thread only: this stage's own thread. Opening a stream, reconnecting
 * for a seek, and reading all happen here. A stalled network read therefore blocks this thread and
 * nothing else. The audio worker keeps handling pause, stop and seek, and keeps the device fed from
 * the chunks that are already queued.
 *
 * <p>Requests come in as tasks and run in order. Each one carries a <em>generation</em>. Starting a
 * new generation discards the queued chunks, and results or chunks from an older generation are
 * dropped. The stage shares the engine's lock and condition, so the worker and this thread wake each
 * other without lost signals. The lock is never held during decoder I/O.
 */
final class DecodeStage {

    /** Chunks read ahead of the audio worker. Bounds memory and how far decoding runs ahead. */
    static final int READ_AHEAD_CHUNKS = 8;

    /** What the audio worker must do with a queued piece. */
    enum PieceKind { AUDIO, END, ERROR }

    /** Decoded bytes, or the end of the stream, or a read failure. */
    record Piece(long generation, PieceKind kind, byte[] data, int length, long frame, Throwable error) {}
    // data is always a pooled buffer; the consumer returns it with recycleLocked.

    /** Which request a completion answers. */
    enum Op { OPEN, SEEK, REWIND }

    /** The result of an open, seek or rewind. {@code failure} is non-null if it failed. */
    record Completion(Op op, long generation, AudioInfo info, long frame, Throwable failure) {}

    private final ReentrantLock lock;
    private final Condition wake;          // the audio worker waits here
    private final Condition space;         // this stage's thread waits here
    private final DecoderProvider provider;
    private final int chunkBytes;
    private final Thread thread;

    // ----- guarded by lock -----
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    private final ArrayDeque<Piece> pieces = new ArrayDeque<>();
    private final ArrayDeque<byte[]> free = new ArrayDeque<>();
    private final List<Completion> completions = new ArrayList<>();
    private long generation;

    // ----- this stage's thread only -----
    private AudioDecoder decoder;
    private boolean pumping;       // read ahead while the queue has room
    private long activeGeneration; // generation of the request that opened or positioned the decoder
    private boolean exit;

    /**
     * @param lock      the engine's lock, shared with the audio worker
     * @param wake      the engine's condition the audio worker waits on; signalled on every change
     *                  the worker needs to see
     * @param provider  opens decoders
     * @param chunkBytes size of each read-ahead buffer
     */
    DecodeStage(ReentrantLock lock, Condition wake, DecoderProvider provider, int chunkBytes) {
        this.lock = Objects.requireNonNull(lock, "lock");
        this.wake = Objects.requireNonNull(wake, "wake");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.chunkBytes = chunkBytes;
        this.space = lock.newCondition();
        this.thread = new Thread(this::run, "playback-decoder");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    // =====================================================================
    // Called by the audio worker (lock held)
    // =====================================================================

    /** Starts a new generation: drops queued chunks and pending completions. Returns the new generation. */
    long beginGenerationLocked() {
        generation++;
        while (!pieces.isEmpty()) {
            free.push(pieces.poll().data());
        }
        completions.clear();
        space.signalAll();
        return generation;
    }

    long currentGenerationLocked() {
        return generation;
    }

    boolean hasPiecesLocked() {
        return !pieces.isEmpty();
    }

    Piece pollPieceLocked() {
        Piece p = pieces.poll();
        if (p != null) {
            space.signal();
        }
        return p;
    }

    /** Returns a buffer whose piece has been consumed. */
    void recycleLocked(byte[] buffer) {
        free.push(buffer);
        space.signal();
    }

    List<Completion> takeCompletionsLocked() {
        if (completions.isEmpty()) {
            return List.of();
        }
        List<Completion> taken = new ArrayList<>(completions);
        completions.clear();
        return taken;
    }

    boolean hasCompletionsLocked() {
        return !completions.isEmpty();
    }

    void openLocked(Track track, long gen) {
        tasks.add(() -> doOpen(track, gen));
        space.signal();
    }

    void seekLocked(Duration target, long gen) {
        tasks.add(() -> doSeek(target, gen));
        space.signal();
    }

    void rewindLocked(Track track, long gen) {
        tasks.add(() -> doRewind(track, gen));
        space.signal();
    }

    /** Closes the current decoder, if any. Ordered after requests already queued. */
    void closeLocked() {
        tasks.add(this::doClose);
        space.signal();
    }

    /** Closes the decoder and ends the thread. Ordered after requests already queued. */
    void shutdownLocked() {
        tasks.add(() -> {
            doClose();
            exit = true;
        });
        space.signal();
    }

    /** Waits for the thread to end, up to {@code timeout}. Interrupts it if it does not. */
    void awaitTermination(Duration timeout) throws InterruptedException {
        thread.join(Math.max(1L, timeout.toMillis()));
        if (thread.isAlive()) {
            thread.interrupt();
        }
    }

    // =====================================================================
    // Stage thread
    // =====================================================================

    private void run() {
        try {
            while (!exit) {
                Runnable task = null;
                lock.lock();
                try {
                    while (tasks.isEmpty() && !(pumping && pieces.size() < READ_AHEAD_CHUNKS)) {
                        space.await();
                    }
                    task = tasks.poll();
                } finally {
                    lock.unlock();
                }
                if (task != null) {
                    task.run();
                } else {
                    readOne();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeDecoderQuietly();
        }
    }

    private void readOne() {
        byte[] buffer;
        long gen;
        lock.lock();
        try {
            buffer = free.isEmpty() ? new byte[chunkBytes] : free.pop();
            gen = activeGeneration;
        } finally {
            lock.unlock();
        }
        try {
            int n = decoder.read(buffer, 0, buffer.length);
            long frame = decoder.framePosition();
            if (n < 0) {
                pumping = false;
                publish(gen, new Piece(gen, PieceKind.END, buffer, 0, frame, null));
            } else {
                publish(gen, new Piece(gen, PieceKind.AUDIO, buffer, n, frame, null));
            }
        } catch (IOException | RuntimeException e) {
            pumping = false;
            publish(gen, new Piece(gen, PieceKind.ERROR, buffer, 0, 0L, e));
        }
    }

    /** Queues a piece for the worker, or returns its buffer to the pool if its generation is over. */
    private void publish(long gen, Piece piece) {
        lock.lock();
        try {
            if (gen != generation) {
                free.push(piece.data());
                return;
            }
            pieces.add(piece);
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void complete(Completion completion) {
        lock.lock();
        try {
            if (completion.generation() == generation) {
                completions.add(completion);
                wake.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    private void doOpen(Track track, long gen) {
        closeDecoderQuietly();
        try {
            AudioDecoder opened = provider.open(track.location());
            decoder = opened;
            activeGeneration = gen;
            pumping = true;
            complete(new Completion(Op.OPEN, gen, opened.audioInfo(), 0L, null));
        } catch (IOException | RuntimeException e) {
            complete(new Completion(Op.OPEN, gen, null, 0L, e));
        }
    }

    private void doSeek(Duration target, long gen) {
        try {
            if (decoder == null) {
                throw new IllegalStateException("no decoder is open");
            }
            decoder.seek(target);
            activeGeneration = gen;
            pumping = true;
            complete(new Completion(Op.SEEK, gen, null, decoder.framePosition(), null));
        } catch (IOException | RuntimeException e) {
            pumping = false;
            complete(new Completion(Op.SEEK, gen, null, 0L, e));
        }
    }

    /**
     * Returns to the start. A seekable decoder seeks to zero. Otherwise the stream is reopened, which
     * is how a non-seekable stream restarts.
     */
    private void doRewind(Track track, long gen) {
        try {
            if (decoder == null) {
                complete(new Completion(Op.REWIND, gen, null, 0L, null));
                return;
            }
            if (decoder.isSeekable()) {
                decoder.seek(Duration.ZERO);
            } else {
                closeDecoderQuietly();
                decoder = provider.open(track.location());
            }
            activeGeneration = gen;
            pumping = true;
            complete(new Completion(Op.REWIND, gen, null, decoder.framePosition(), null));
        } catch (IOException | RuntimeException e) {
            pumping = false;
            complete(new Completion(Op.REWIND, gen, null, 0L, e));
        }
    }

    private void doClose() {
        pumping = false;
        closeDecoderQuietly();
    }

    private void closeDecoderQuietly() {
        pumping = false;
        AudioDecoder d = decoder;
        decoder = null;
        if (d != null) {
            try {
                d.close();
            } catch (IOException | RuntimeException ignored) {
                // Closing a finished or broken stream has nothing useful to report.
            }
        }
    }
}
