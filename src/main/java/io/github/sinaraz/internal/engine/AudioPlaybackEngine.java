package io.github.sinaraz.internal.engine;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import javax.sound.sampled.AudioFormat;

import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.audio.AudioOutput;
import io.github.sinaraz.audio.DecoderProvider;
import io.github.sinaraz.error.PlaybackStateException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.internal.processing.PlaybackPipeline;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.Track;

/**
 * Plays one track at a time through an {@link AudioOutput}.
 *
 * <h2>Threading model</h2>
 * <ul>
 *   <li><b>Control threads</b> (callers of the public methods) validate the request against the
 *       {@link PlaybackState} table while holding {@code lock}, record it as a <em>pending
 *       request</em>, update the public state, and signal the worker. They never touch the decoder
 *       or the output device, so they never block on audio I/O.</li>
 *   <li><b>The decode stage</b> ({@link DecodeStage}) is the only thread that uses the decoder. It
 *       opens, seeks and reads ahead into a bounded queue of chunks, so a slow network read blocks
 *       only that thread.</li>
 *   <li><b>The audio worker</b> uses the output device and the PCM pipeline. It takes pending requests
 *       in a fixed order (unload, load, seek, stop), applies the decode stage's results, starts or
 *       stops the device to match the state, and plays one queued chunk at a time. It never waits on
 *       the decoder. Blocking {@code SourceDataLine.write} calls happen only on this thread and never
 *       while {@code lock} is held.</li>
 *   <li><b>Listeners</b> are never called here. Events are queued on the {@link PlaybackEventBus}.</li>
 * </ul>
 *
 * <p>Every state change is validated by {@link PlaybackState#canTransitionTo}. Worker completions
 * check the state they expect before transitioning, so a request that arrives while the worker is
 * busy is never overwritten by a stale completion.
 *
 * <h2>Pause latency</h2>
 * Pausing is effective after the in-flight {@code write} returns, at most one device buffer later.
 * The device buffer itself is kept, so resume is immediate and nothing is replayed or dropped.
 *
 * <h2>End of track</h2>
 * At end of stream the worker drains the device so the last audio is heard, then moves to
 * {@code ENDED} and publishes {@code onPlaybackCompleted}. Advancing the queue is the caller's job.
 */
public final class AudioPlaybackEngine implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(AudioPlaybackEngine.class.getName());

    private final PlaybackConfig config;
    private final DecoderProvider decoderFactory;
    private final Supplier<AudioOutput> outputFactory;
    private final PlaybackEventBus bus;
    private final Supplier<QueueSnapshot> queueState;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wake = lock.newCondition();
    /** Signalled when the worker has stopped the output after a pause. */
    private final Condition outputSettled = lock.newCondition();
    /** Upper bound on how long pause() waits for the in-flight device write to finish. */
    private static final Duration SETTLE_TIMEOUT = Duration.ofSeconds(2);
    /** Poll interval while the device plays out the final buffer. */
    private static final long PLAYOUT_POLL_NANOS = 20_000_000L;
    private boolean settlePending;   // guarded by lock: pause() asked the worker to stop the output
    private long settleEpoch;        // guarded by lock: incremented after each worker pass that is not playing
    private final DecodeStage stage;
    private final Thread worker;

    // ----- guarded by lock -----
    private PlaybackState state = PlaybackState.EMPTY;
    private Track track;
    private AudioInfo info;
    private Duration position = Duration.ZERO;
    private Duration duration = Duration.ZERO;
    private double volume = 1.0;
    private boolean muted;
    private double speed = 1.0;
    private boolean preservePitch;
    private double pan;
    private PlaybackError error;
    private boolean closed;
    private long volumeGeneration;
    private Track pendingLoad;
    private boolean pendingUnload;
    private boolean pendingStop;
    private Duration pendingSeek;
    private PlaybackState seekResume = PlaybackState.PLAYING;
    private boolean pendingAutoPlay;

    // ----- audio worker only -----
    private AudioOutput output;
    private AudioFormat outputFormat;
    private final PlaybackPipeline pipeline = new PlaybackPipeline();
    private boolean outputRunning;
    private long appliedVolumeGeneration = -1L;
    private boolean hardwareVolume;
    private float softGain = 1.0f;
    private double workerSpeed = 1.0;
    private boolean workerPreservePitch;
    private float workerPan;
    private Track loadingTrack;
    private long lastEndFrame;     // frame position of the last chunk handed to the pipeline
    private long lastProgressNanos;

    /** A snapshot of pending requests, taken under the lock. */
    private record Work(
            Track load,
            boolean unload,
            Duration seek,
            boolean stop,
            double speed,
            boolean preservePitch,
            float pan,
            long volumeGeneration,
            double volume,
            boolean muted,
            long generation,
            List<DecodeStage.Completion> completions) {}

    /**
     * Creates the engine. Starts no threads until the first track is opened.
     *
     * @param config       playback tuning and limits
     * @param decoderFactory opens decoders for queued files
     * @param outputFactory  creates an audio output for each opened format
     * @param bus          receives events; listeners run on its thread
     * @param queueState   supplies queue information for the status line
     */
    public AudioPlaybackEngine(PlaybackConfig config,
                               DecoderProvider decoderFactory,
                               Supplier<AudioOutput> outputFactory,
                               PlaybackEventBus bus,
                               Supplier<QueueSnapshot> queueState) {
        this.config = Objects.requireNonNull(config, "config");
        this.decoderFactory = Objects.requireNonNull(decoderFactory, "decoderFactory");
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
        this.bus = Objects.requireNonNull(bus, "bus");
        this.queueState = Objects.requireNonNull(queueState, "queueState");
        this.stage = new DecodeStage(lock, wake, decoderFactory, config.chunkBytes());
        this.stage.start();
        this.worker = new Thread(this::runWorker, "playback-audio-worker");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    // =====================================================================
    // Control API (any thread)
    // =====================================================================

    /** Loads a track. The decoder opens on the worker; watch for {@code onTrackLoaded} or {@code onError}. */
    public void load(Track next) {
        Objects.requireNonNull(next, "track");
        lock.lock();
        try {
            requireOpenLocked("load");
            pendingLoad = next;
            pendingUnload = false;
            pendingStop = false;
            pendingSeek = null;
            pendingAutoPlay = false;
            transitionLocked(PlaybackState.LOADING);
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Clears the loaded track and stops output. A no-op when already empty. */
    public void unload() {
        lock.lock();
        try {
            requireOpenLocked("unload");
            if (state == PlaybackState.EMPTY) {
                return;
            }
            Track previous = track;
            pendingUnload = true;
            pendingLoad = null;
            pendingStop = false;
            pendingSeek = null;
            pendingAutoPlay = false;
            track = null;
            info = null;
            duration = Duration.ZERO;
            position = Duration.ZERO;
            error = null;
            transitionLocked(PlaybackState.EMPTY);
            bus.publish(l -> l.onTrackChanged(previous, null));
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Starts or continues playback. From {@code LOADING} it starts as soon as the load completes.
     * From {@code ENDED} it restarts the track from the beginning.
     */
    public void play() {
        lock.lock();
        try {
            requireOpenLocked("play");
            switch (state) {
                case LOADING -> pendingAutoPlay = true;
                case READY, PAUSED, STOPPED -> transitionLocked(PlaybackState.PLAYING);
                case ENDED -> {
                    pendingSeek = Duration.ZERO;
                    seekResume = PlaybackState.PLAYING;
                    transitionLocked(PlaybackState.SEEKING);
                }
                case SEEKING -> seekResume = PlaybackState.PLAYING;
                case STOPPING -> pendingAutoPlay = true;
                case PLAYING -> { /* already playing */ }
                case EMPTY -> throw new PlaybackStateException("play", state, "no track is loaded");
                case ERROR -> throw new PlaybackStateException("play", state,
                        "the last track failed; load a track first");
                default -> throw new PlaybackStateException("play", state, "cannot start playback");
            }
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Pauses playback. Returns once the audio output has stopped and the reported position is
     * final, which takes at most one device write (bounded by {@link #SETTLE_TIMEOUT}).
     */
    public void pause() {
        lock.lock();
        try {
            requireOpenLocked("pause");
            boolean settle = false;
            switch (state) {
                case PLAYING -> {
                    transitionLocked(PlaybackState.PAUSED);
                    settle = true;
                }
                case PAUSED -> { /* already paused */ }
                case SEEKING -> seekResume = PlaybackState.PAUSED;
                case STOPPING -> pendingAutoPlay = false;
                default -> throw new PlaybackStateException("pause", state, "nothing is playing");
            }
            wake.signalAll();
            if (settle) {
                awaitOutputSettledLocked();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Caller holds the lock. Waits until the worker completes a pass in which playback is stopped. */
    private void awaitOutputSettledLocked() {
        long seen = settleEpoch;
        settlePending = true;
        wake.signalAll();
        long remaining = SETTLE_TIMEOUT.toNanos();
        while (settleEpoch == seen && !closed && remaining > 0) {
            try {
                remaining = outputSettled.awaitNanos(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Called by the worker after it has stopped the output for a pass that is not playing. */
    private void markSettled() {
        lock.lock();
        try {
            if (settlePending && state == PlaybackState.PAUSED) {
                publishStateLocked(); // corrected position; the earlier PAUSED snapshot was taken before the stop
            }
            settlePending = false;
            settleEpoch++;
            outputSettled.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resumes a paused track. The position continues from where it paused.
     */
    public void resume() {
        lock.lock();
        try {
            requireOpenLocked("resume");
            switch (state) {
                case PAUSED -> transitionLocked(PlaybackState.PLAYING);
                case PLAYING -> { /* already playing */ }
                case SEEKING -> seekResume = PlaybackState.PLAYING;
                default -> throw new PlaybackStateException("resume", state, "playback is not paused");
            }
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Stops playback and rewinds to the start. The track stays loaded. */
    public void stop() {
        lock.lock();
        try {
            requireOpenLocked("stop");
            switch (state) {
                case PLAYING, PAUSED, READY, ENDED -> {
                    pendingStop = true;
                    pendingSeek = null;
                    pendingAutoPlay = false;
                    transitionLocked(PlaybackState.STOPPING);
                }
                case SEEKING -> {
                    pendingStop = true;
                    pendingSeek = null;
                    pendingAutoPlay = false;
                    transitionLocked(PlaybackState.STOPPING);
                }
                case STOPPING -> pendingAutoPlay = false;
                case STOPPED -> { /* already stopped */ }
                default -> throw new PlaybackStateException("stop", state, "nothing to stop");
            }
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Seeks to an absolute position. The value is clamped to {@code [0, duration]}.
     *
     * @throws SeekNotSupportedException if the current source cannot be repositioned
     */
    public void seek(Duration target) {
        Objects.requireNonNull(target, "target");
        lock.lock();
        try {
            seekLocked(target);
        } finally {
            lock.unlock();
        }
    }

    /** Seeks relative to the current position. Negative offsets seek backwards. */
    public void seekRelative(Duration offset) {
        Objects.requireNonNull(offset, "offset");
        lock.lock();
        try {
            seekLocked(position.plus(offset));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Sets the linear volume.
     *
     * @param value volume in [0, 1]
     */
    public void setVolume(double value) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("volume must not be NaN");
        }
        lock.lock();
        try {
            requireOpenLocked("setVolume");
            volume = Math.max(0.0, Math.min(1.0, value));
            volumeGeneration++;
            publishStateLocked();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the linear volume in [0, 1].
     */
    public double volume() {
        lock.lock();
        try {
            return volume;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Mutes or unmutes the output without changing the volume.
     */
    public void setMuted(boolean value) {
        lock.lock();
        try {
            requireOpenLocked("setMuted");
            muted = value;
            volumeGeneration++;
            publishStateLocked();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns whether output is muted.
     */
    public boolean isMuted() {
        lock.lock();
        try {
            return muted;
        } finally {
            lock.unlock();
        }
    }

    /** Sets the speed factor, clamped to the configured range. Note that this changes pitch. */
    public void setPlaybackSpeed(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException("speed must be finite: " + value);
        }
        lock.lock();
        try {
            requireOpenLocked("setPlaybackSpeed");
            speed = Math.max(config.minSpeed(), Math.min(config.maxSpeed(), value));
            publishStateLocked();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the current playback speed multiplier.
     */
    public double playbackSpeed() {
        lock.lock();
        try {
            return speed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Turns pitch-preserving time-stretching on or off. When on, speed changes tempo but not pitch
     * (WSOLA). When off, speed resamples the audio, so pitch changes with speed. Takes effect on the
     * next audio block.
     */
    public void setPreservePitch(boolean value) {
        lock.lock();
        try {
            requireOpenLocked("setPreservePitch");
            preservePitch = value;
            publishStateLocked();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Whether pitch-preserving time-stretching is on. */
    public boolean isPreservePitch() {
        lock.lock();
        try {
            return preservePitch;
        } finally {
            lock.unlock();
        }
    }

    /** The file path of a local track, or {@code null} for a stream. */
    private static Path localPath(Track t) {
        return t.location() instanceof MediaLocation.LocalFile(Path path) ? path : null;
    }

    /** Sets stereo pan in {@code [-1, 1]}: -1 full left, 0 centre, +1 full right. */
    public void setPan(double value) {
        if (Double.isNaN(value)) {
            throw new IllegalArgumentException("pan must not be NaN");
        }
        lock.lock();
        try {
            requireOpenLocked("setPan");
            pan = Math.max(-1.0, Math.min(1.0, value));
            publishStateLocked();
            wake.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns the stereo pan position in [-1, 1].
     */
    public double pan() {
        lock.lock();
        try {
            return pan;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Returns a consistent copy of the current playback state.
     */
    public PlaybackSnapshot snapshot() {
        lock.lock();
        try {
            return snapshotLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Idempotent. Stops the worker, closes the decoder and the device, and blocks up to the configured timeout. */
    @Override
    public void close() {
        lock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            state = PlaybackState.CLOSED;
            PlaybackSnapshot snap = snapshotLocked();
            bus.publish(l -> l.onStateChanged(snap));
            wake.signalAll();
        } finally {
            lock.unlock();
        }
        if (Thread.currentThread() == worker) {
            return;
        }
        long deadline = System.nanoTime() + config.closeTimeout().toNanos();
        try {
            worker.join(Math.max(1L, remainingMillis(deadline)));
            if (worker.isAlive()) {
                LOG.log(System.Logger.Level.WARNING, "audio worker did not stop in time; interrupting");
                worker.interrupt();
            }
            stage.awaitTermination(Duration.ofMillis(Math.max(1L, remainingMillis(deadline))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =====================================================================
    // Control helpers (lock held)
    // =====================================================================

    private void seekLocked(Duration target) {
        requireOpenLocked("seek");
        if (track == null || info == null) {
            throw new PlaybackStateException("seek", state, "no track is loaded");
        }
        boolean seekable = switch (state) {
            case READY, PLAYING, PAUSED, STOPPED, ENDED, SEEKING -> true;
            default -> false;
        };
        if (!seekable) {
            throw new PlaybackStateException("seek", state, "seeking is not possible in this state");
        }
        if (!info.seekable()) {
            throw new SeekNotSupportedException("seek",
                    "this source cannot be repositioned", localPath(track));
        }
        Duration upper = duration.isZero() ? Duration.ofNanos(Long.MAX_VALUE) : duration;
        pendingSeek = target.isNegative() ? Duration.ZERO : (target.compareTo(upper) > 0 ? upper : target);
        if (state != PlaybackState.SEEKING) {
            seekResume = switch (state) {
                case PLAYING -> PlaybackState.PLAYING;
                case PAUSED, ENDED -> PlaybackState.PAUSED;
                case READY -> PlaybackState.READY;
                case STOPPED -> PlaybackState.STOPPED;
                default -> throw new IllegalStateException("unreachable: " + state);
            };
            transitionLocked(PlaybackState.SEEKING);
        }
        wake.signalAll();
    }

    private static long remainingMillis(long deadlineNanos) {
        return (deadlineNanos - System.nanoTime()) / 1_000_000L;
    }

    private void requireOpenLocked(String operation) {
        if (closed) {
            throw new PlaybackStateException(operation, PlaybackState.CLOSED, "player is closed");
        }
    }

    private void transitionLocked(PlaybackState next) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("illegal transition " + state + " -> " + next);
        }
        state = next;
        publishStateLocked();
    }

    private void publishStateLocked() {
        PlaybackSnapshot snap = snapshotLocked();
        bus.publish(l -> l.onStateChanged(snap));
    }

    private PlaybackSnapshot snapshotLocked() {
        QueueSnapshot q = queueState.get();
        return new PlaybackSnapshot(track, state, position, duration, volume, muted, speed, pan, preservePitch,
                q.currentIndex(), q.size(), q.repeat(), q.shuffle(), error);
    }

    private boolean hasWorkLocked() {
        return pendingLoad != null
                || pendingUnload
                || pendingSeek != null
                || pendingStop
                || stage.hasCompletionsLocked()
                || (state == PlaybackState.PLAYING && stage.hasPiecesLocked())
                || (outputRunning && state != PlaybackState.PLAYING)
                || settlePending
                || (output != null && volumeGeneration != appliedVolumeGeneration);
    }

    private PlaybackState currentStateLocked() {
        return state;
    }

    // =====================================================================
    // Audio worker
    // =====================================================================

    /*
     * The worker never touches the decoder. DecodeStage does all decoder I/O on its own thread and
     * hands back chunks and completions. The worker only drives the output device and the state.
     */

    private void runWorker() {
        try {
            while (true) {
                Work work = awaitWork();
                if (work == null) {
                    break;
                }
                try {
                    workerSpeed = work.speed();
                    workerPreservePitch = work.preservePitch();
                    workerPan = work.pan();
                    if (work.unload()) {
                        doUnload();
                    }
                    if (work.load() != null) {
                        doLoad(work.load(), work.generation());
                    } else if (work.seek() != null) {
                        doSeek(work.seek(), work.generation());
                    }
                    if (work.stop()) {
                        doStop(work.generation());
                    }
                    for (DecodeStage.Completion completion : work.completions()) {
                        handleCompletion(completion);
                    }
                    applyVolumeIfChanged(work);
                    PlaybackState current;
                    lock.lock();
                    try {
                        current = currentStateLocked();
                    } finally {
                        lock.unlock();
                    }
                    if (current != PlaybackState.PLAYING) {
                        stopOutputIfRunning();
                        markSettled();
                    } else if (info != null && output != null) {
                        if (!outputRunning) {
                            output.start();
                            outputRunning = true;
                        }
                        renderChunk();
                    }
                } catch (RuntimeException e) {
                    LOG.log(System.Logger.Level.ERROR, "unexpected failure on audio worker", e);
                    fail("worker", null, e);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            releaseResources();
        }
    }

    /**
     * Waits for work, then takes a snapshot of it. Taking a load, seek, stop or unload starts a new
     * generation. That drops queued chunks and any completions still in flight from the old one.
     */
    private Work awaitWork() throws InterruptedException {
        lock.lock();
        try {
            while (!closed && !hasWorkLocked()) {
                wake.await();
            }
            if (closed) {
                return null;
            }
            boolean newRequest = pendingLoad != null || pendingUnload || pendingSeek != null || pendingStop;
            long gen = newRequest ? stage.beginGenerationLocked() : stage.currentGenerationLocked();
            Work work = new Work(pendingLoad, pendingUnload, pendingSeek, pendingStop,
                    speed, preservePitch, (float) pan, volumeGeneration, volume, muted,
                    gen, stage.takeCompletionsLocked());
            pendingLoad = null;
            pendingUnload = false;
            pendingSeek = null;
            pendingStop = false;
            return work;
        } finally {
            lock.unlock();
        }
    }

    /** Stops the output and hands the open to the decode stage. The result arrives as a completion. */
    private void doLoad(Track next, long gen) {
        haltOutput();
        pipeline.reset();
        loadingTrack = next;
        lock.lock();
        try {
            stage.openLocked(next, gen);
        } finally {
            lock.unlock();
        }
    }

    /** Applies a finished open. Ignored if a newer request has arrived, which will be handled next. */
    private void completeLoad(Track next, AudioInfo newInfo) {
        if (newInfo.channels() > 2) {
            throw new UnsupportedAudioFormatException("load",
                    "more than two channels is not supported", localPath(next), null);
        }
        ensureOutput(newInfo);
        pipeline.prepare(newInfo.sampleRate());
        lastEndFrame = 0L;

        lock.lock();
        try {
            if (closed || pendingLoad != null || pendingUnload) {
                return;
            }
            Track previous = track;
            info = newInfo;
            track = next;
            duration = newInfo.duration();
            position = Duration.ZERO;
            error = null;
            lastProgressNanos = 0L;
            PlaybackState target = pendingAutoPlay ? PlaybackState.PLAYING : PlaybackState.READY;
            pendingAutoPlay = false;
            bus.publish(l -> l.onTrackChanged(previous, next));
            bus.publish(l -> l.onTrackLoaded(next, newInfo));
            transitionLocked(target);
        } finally {
            lock.unlock();
        }
    }

    private void doSeek(Duration target, long gen) {
        haltOutput();
        pipeline.reset();
        lock.lock();
        try {
            stage.seekLocked(target, gen);
        } finally {
            lock.unlock();
        }
    }

    private void doStop(long gen) {
        stopOutputIfRunning();
        if (output != null) {
            output.flush();
        }
        pipeline.reset();
        lock.lock();
        try {
            stage.rewindLocked(track, gen);
        } finally {
            lock.unlock();
        }
    }

    private void doUnload() {
        lock.lock();
        try {
            stage.closeLocked();
        } finally {
            lock.unlock();
        }
        stopOutputIfRunning();
        if (output != null) {
            output.flush();
        }
        pipeline.reset();
    }

    /** Applies a result from the decode stage. Results from an older generation never reach here. */
    private void handleCompletion(DecodeStage.Completion c) {
        switch (c.op()) {
            case OPEN -> {
                if (c.failure() != null) {
                    fail("load", loadingTrack, c.failure());
                } else {
                    try {
                        completeLoad(loadingTrack, c.info());
                    } catch (RuntimeException e) {
                        fail("load", loadingTrack, e);
                    }
                }
            }
            case SEEK -> {
                if (c.failure() != null) {
                    fail("seek", null, c.failure());
                    return;
                }
                lastEndFrame = c.frame();
                lock.lock();
                try {
                    position = framesToDuration(c.frame());
                    // Only finish the seek if nothing newer arrived during it; otherwise the next iteration handles it.
                    if (state == PlaybackState.SEEKING && pendingSeek == null) {
                        transitionLocked(seekResume);
                    } else {
                        publishStateLocked();
                    }
                } finally {
                    lock.unlock();
                }
            }
            case REWIND -> {
                if (c.failure() != null) {
                    fail("stop", null, c.failure());
                    return;
                }
                lastEndFrame = c.frame();
                lock.lock();
                try {
                    position = Duration.ZERO;
                    if (state == PlaybackState.STOPPING) {
                        PlaybackState target = pendingAutoPlay ? PlaybackState.PLAYING : PlaybackState.STOPPED;
                        pendingAutoPlay = false;
                        transitionLocked(target);
                    }
                } finally {
                    lock.unlock();
                }
            }
        }
    }

    private void applyVolumeIfChanged(Work work) {
        if (output == null || work.volumeGeneration() == appliedVolumeGeneration) {
            return;
        }
        hardwareVolume = output.applyVolume(work.volume(), work.muted());
        softGain = hardwareVolume ? 1.0f : (work.muted() ? 0.0f : (float) work.volume());
        appliedVolumeGeneration = work.volumeGeneration();
    }

    /** Plays one queued chunk, if there is one. Never waits on the decoder. */
    private void renderChunk() {
        DecodeStage.Piece piece;
        lock.lock();
        try {
            if (state != PlaybackState.PLAYING) {
                return;
            }
            piece = stage.pollPieceLocked();
        } finally {
            lock.unlock();
        }
        if (piece == null) {
            return;
        }
        switch (piece.kind()) {
            case ERROR -> {
                recycle(piece);
                fail("read", null, piece.error());
            }
            case END -> {
                recycle(piece);
                handleEndOfStream();
            }
            case AUDIO -> {
                int pcmBytes = pipeline.process(piece.data(), piece.length(), info.channels(),
                        workerSpeed, workerPreservePitch, softGain, workerPan);
                recycle(piece);
                lastEndFrame = piece.frame();
                if (pcmBytes > 0) {
                    try {
                        output.write(pipeline.output(), 0, pcmBytes);
                    } catch (RuntimeException e) {
                        fail("write", null, e);
                        return;
                    }
                }
                publishProgressIfDue();
            }
        }
    }

    private void recycle(DecodeStage.Piece piece) {
        lock.lock();
        try {
            stage.recycleLocked(piece.data());
        } finally {
            lock.unlock();
        }
    }

    private void handleEndOfStream() {
        try {
            int tailBytes = pipeline.finish(softGain, workerPan);
            if (tailBytes > 0) {
                output.write(pipeline.output(), 0, tailBytes);
            }
            if (!awaitPlayedOut()) {
                return; // paused or stopped while the tail played; end of stream is seen again on resume
            }
            output.drain();
        } catch (RuntimeException e) {
            fail("drain", null, e);
            return;
        }
        output.stop();
        outputRunning = false;
        Track finished;
        boolean ended = false;
        lock.lock();
        try {
            finished = track;
            if (state == PlaybackState.PLAYING) {
                position = duration;
                transitionLocked(PlaybackState.ENDED);
                ended = true;
            }
        } finally {
            lock.unlock();
        }
        if (ended && finished != null) {
            bus.publish(l -> l.onPlaybackCompleted(finished));
        }
    }

    /**
     * Waits for the device to play out what has been written, publishing progress while it does.
     * The decode stage may have read ahead of the device by a full buffer, so without this the position
     * would stay frozen until the end. Returns {@code false} if playback left the PLAYING state.
     */
    private boolean awaitPlayedOut() {
        while (output.bufferedFrames() > 0) {
            lock.lock();
            PlaybackState now;
            try {
                now = state;
            } finally {
                lock.unlock();
            }
            if (now != PlaybackState.PLAYING) {
                return false;
            }
            publishProgressIfDue();
            LockSupport.parkNanos(PLAYOUT_POLL_NANOS);
        }
        return true;
    }

    private void publishProgressIfDue() {
        long now = System.nanoTime();
        if (now - lastProgressNanos < config.progressInterval().toNanos()) {
            return;
        }
        lastProgressNanos = now;
        lock.lock();
        try {
            position = estimatePosition();
            bus.publishProgress(snapshotLocked());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Playback position = frames of the last chunk handed to the pipeline, minus frames still queued in
     * the pipeline and the device. Speed scales the device backlog into source frames. Uses the frame
     * carried on the chunk, so it never reads the decoder from this thread.
     */
    private Duration estimatePosition() {
        if (info == null) {
            return Duration.ZERO;
        }
        long delivered = lastEndFrame;
        long buffered = output != null ? output.bufferedFrames() : 0L;
        long behind = Math.round(buffered * workerSpeed) + pipeline.latencyFrames();
        long frames = Math.max(0L, delivered - behind);
        if (info.frameCount() > 0) {
            frames = Math.min(frames, info.frameCount());
        }
        return framesToDuration(frames);
    }

    private Duration framesToDuration(long frames) {
        int rate = info != null ? info.sampleRate() : 44_100;
        return Duration.ofNanos(Math.round(frames * 1_000_000_000.0 / rate));
    }

    /** Opens the output device for this stream's rate, reusing it when the format is unchanged. */
    private void ensureOutput(AudioInfo newInfo) {
        AudioFormat wanted = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                newInfo.sampleRate(), 16, 2, 4, newInfo.sampleRate(), false);
        if (output != null && outputFormat != null && outputFormat.matches(wanted)) {
            return;
        }
        if (output != null) {
            output.close();
            output = null;
            outputFormat = null;
        }
        AudioOutput fresh = outputFactory.get();
        try {
            fresh.open(wanted);
        } catch (RuntimeException e) {
            closeQuietlyOutput(fresh);
            throw e;
        }
        output = fresh;
        outputFormat = wanted;
        outputRunning = false;
        appliedVolumeGeneration = -1L;
    }

    /** Stops the device if it is running and discards any audio still queued in it. */
    private void haltOutput() {
        stopOutputIfRunning();
        if (output != null) {
            output.flush();
        }
    }

    private void stopOutputIfRunning() {
        if (outputRunning && output != null) {
            updatePositionEstimate();
            output.stop();
            outputRunning = false;
        }
    }

    private void updatePositionEstimate() {
        lock.lock();
        try {
            position = estimatePosition();
        } finally {
            lock.unlock();
        }
    }

    private void fail(String operation, Track failing, Throwable cause) {
        PlaybackError err;
        boolean superseded;
        lock.lock();
        try {
            Track subject = failing != null ? failing : track;
            err = PlaybackError.from(operation, subject, cause);
            superseded = closed || pendingLoad != null || pendingUnload;
            stage.closeLocked();
        } finally {
            lock.unlock();
        }
        if (output != null) {
            try {
                output.stop();
                output.flush();
            } catch (RuntimeException ignored) {
                // The device is already failing; the error being reported is the important one.
            }
        }
        outputRunning = false;
        if (superseded) {
            return;
        }
        lock.lock();
        try {
            error = err;
            if (state.canTransitionTo(PlaybackState.ERROR)) {
                transitionLocked(PlaybackState.ERROR);
            } else {
                publishStateLocked();
            }
        } finally {
            lock.unlock();
        }
        bus.publish(l -> l.onError(err));
    }

    private void closeQuietlyOutput(AudioOutput o) {
        try {
            o.close();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.DEBUG, "error while closing output", e);
        }
    }

    private void releaseResources() {
        lock.lock();
        try {
            stage.shutdownLocked();
        } finally {
            lock.unlock();
        }
        if (output != null) {
            try {
                output.stop();
                output.flush();
            } catch (RuntimeException ignored) {
                // Closing anyway.
            }
            closeQuietlyOutput(output);
            output = null;
            outputFormat = null;
            outputRunning = false;
        }
    }
}
