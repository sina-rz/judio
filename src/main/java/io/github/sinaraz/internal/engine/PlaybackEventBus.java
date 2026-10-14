package io.github.sinaraz.internal.engine;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.PlaybackSnapshot;

/**
 * Delivers events to listeners on one dedicated thread, preserving order.
 *
 * <ul>
 *   <li>Publishing never blocks: events are handed to an executor, so the audio worker and
 *       control threads never wait for listener code.</li>
 *   <li>Progress is coalesced. If the event thread is slow, only the newest progress snapshot is
 *       delivered, which bounds the backlog to one pending progress event.</li>
 *   <li>A listener that throws is logged and skipped for that event only.</li>
 *   <li>After {@link #close()} no new events are accepted; events already queued still run.</li>
 * </ul>
 */
public final class PlaybackEventBus implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(PlaybackEventBus.class.getName());

    private final CopyOnWriteArrayList<PlaybackListener> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Thread> dispatchThread = new AtomicReference<>();
    private final ExecutorService executor;
    private final AtomicReference<PlaybackSnapshot> pendingProgress = new AtomicReference<>();
    private final AtomicBoolean progressQueued = new AtomicBoolean(false);
    /** Guards pendingProgress/progressQueued so take-and-clear is atomic. */
    private final Object progressLock = new Object();
    private final Duration closeTimeout;
    private volatile boolean closed;

    public PlaybackEventBus(Duration closeTimeout) {
        this.closeTimeout = Objects.requireNonNull(closeTimeout, "closeTimeout");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "player-events");
            thread.setDaemon(true);
            dispatchThread.set(thread);
            return thread;
        });
    }

    public void addListener(PlaybackListener listener) {
        // addIfAbsent: registering the same instance twice has no extra effect,
        // as documented on JudioPlayer.addListener.
        listeners.addIfAbsent(Objects.requireNonNull(listener, "listener"));
    }

    public void removeListener(PlaybackListener listener) {
        listeners.remove(listener);
    }

    /** Queues a non-progress event. */
    public void publish(Consumer<PlaybackListener> event) {
        Objects.requireNonNull(event, "event");
        if (closed) {
            return;
        }
        try {
            executor.execute(() -> dispatch(event));
        } catch (RejectedExecutionException ignored) {
            // Closed concurrently: dropping events after shutdown is intended.
        }
    }

    /** Queues a progress snapshot, replacing any older one that has not been delivered yet. */
    public void publishProgress(PlaybackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (closed) {
            return;
        }
        boolean enqueue;
        synchronized (progressLock) {
            pendingProgress.set(snapshot);
            enqueue = progressQueued.compareAndSet(false, true);
        }
        if (enqueue) {
            try {
                executor.execute(this::deliverPendingProgress);
            } catch (RejectedExecutionException ignored) {
                synchronized (progressLock) {
                    progressQueued.set(false);
                }
            }
        }
    }

    private void deliverPendingProgress() {
        PlaybackSnapshot snapshot;
        synchronized (progressLock) {
            // Take the snapshot and clear the flag atomically: a publisher racing
            // this method either lands in this delivery or queues a new one,
            // never neither, and never observes a newer snapshot out of order.
            snapshot = pendingProgress.getAndSet(null);
            progressQueued.set(false);
        }
        if (snapshot != null) {
            dispatch(listener -> listener.onProgress(snapshot));
        }
    }

    private void dispatch(Consumer<PlaybackListener> event) {
        for (PlaybackListener listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "playback listener " + listener + " failed", e);
            }
        }
    }

    /**
     * Stops accepting events and waits (bounded) for queued events to finish. Safe to call from a
     * listener: it does not wait for itself.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        executor.shutdown();
        if (Thread.currentThread() == dispatchThread.get()) {
            return;
        }
        try {
            if (!executor.awaitTermination(closeTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "event thread did not finish within " + closeTimeout);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
