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
import java.util.function.Supplier;

import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.internal.lifecycle.CloseDeadline;
import io.github.sinaraz.internal.queue.QueueManager;
import io.github.sinaraz.model.PlaybackSnapshot;

/**
 * Delivers events to listeners on one dedicated thread, preserving event order.
 *
 * <ul>
 *   <li>Publishing never blocks for listener code, so audio and control threads never wait on a
 *       callback.</li>
 *   <li>Progress and consecutive queue-state notifications are coalesced. When the event thread is
 *       behind, the newest snapshot in each uninterrupted run is delivered.</li>
 *   <li>Queue snapshots are materialized only when delivered, avoiding a retained full queue copy
 *       per update. A snapshot is a consistent view at callback dispatch, not a historical journal.</li>
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
    /** Guards submission order, pendingQueue, and publication versus shutdown. */
    private final Object submissionLock = new Object();
    /** Guards pendingProgress/progressQueued so take-and-clear is atomic. */
    private final Object progressLock = new Object();
    private final Duration closeTimeout;
    private PendingQueueNotification pendingQueue;
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

    /** Queues a non-progress event and ends the preceding queue-notification coalescing run. */
    public void publish(Consumer<PlaybackListener> event) {
        Objects.requireNonNull(event, "event");
        synchronized (submissionLock) {
            if (closed) {
                return;
            }
            endQueueCoalescingRunLocked();
            try {
                executor.execute(() -> dispatch(event));
            } catch (RejectedExecutionException ignored) {
                // Closed concurrently: dropping events after shutdown is intended.
            }
        }
    }

    /**
     * Queues a queue-state notification. Consecutive updates replace the pending snapshot supplier.
     * A following non-queue event ends that coalescing run, preserving callback publication order.
     */
    public void publishQueueChanged(Supplier<QueueManager.View> snapshotSupplier) {
        Objects.requireNonNull(snapshotSupplier, "snapshotSupplier");
        synchronized (submissionLock) {
            if (closed) {
                return;
            }
            if (pendingQueue != null) {
                pendingQueue.replace(snapshotSupplier);
                return;
            }
            PendingQueueNotification notification = new PendingQueueNotification(snapshotSupplier);
            pendingQueue = notification;
            try {
                executor.execute(() -> deliverQueueChanged(notification));
            } catch (RejectedExecutionException ignored) {
                pendingQueue = null;
            }
        }
    }

    /** Queues progress snapshot, replacing any older one that has not been delivered yet. */
    public void publishProgress(PlaybackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        synchronized (submissionLock) {
            if (closed) {
                return;
            }
            boolean enqueue;
            synchronized (progressLock) {
                pendingProgress.set(snapshot);
                enqueue = progressQueued.compareAndSet(false, true);
            }
            if (enqueue) {
                endQueueCoalescingRunLocked();
                try {
                    executor.execute(this::deliverPendingProgress);
                } catch (RejectedExecutionException ignored) {
                    synchronized (progressLock) {
                        progressQueued.set(false);
                    }
                }
            }
        }
    }

    private void endQueueCoalescingRunLocked() {
        // The queued notification still runs using its lazy supplier; clearing only the marker
        // preserves event order without copying a potentially large queue on the publishing thread.
        pendingQueue = null;
    }

    private void deliverQueueChanged(PendingQueueNotification notification) {
        Supplier<QueueManager.View> snapshotSupplier;
        synchronized (submissionLock) {
            if (pendingQueue == notification) {
                pendingQueue = null;
            }
            snapshotSupplier = notification.snapshotSupplier();
        }
        // A full queue copy can be large. Do it off the submission lock so the audio/control
        // threads can continue publishing unrelated events while listeners are caught up.
        QueueManager.View view = snapshotSupplier.get();
        dispatch(listener -> listener.onQueueChanged(view.tracks(), view.state()));
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
        close(CloseDeadline.after(closeTimeout));
    }

    /** Stops accepting events and waits only for the time remaining on a shared player-close deadline. */
    public void close(CloseDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        synchronized (submissionLock) {
            if (closed) {
                return;
            }
            closed = true;
            pendingQueue = null;
            executor.shutdown();
        }
        if (Thread.currentThread() == dispatchThread.get()) {
            return;
        }
        try {
            if (!executor.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                LOG.log(System.Logger.Level.WARNING, "event thread did not finish before the close deadline");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }

    private static final class PendingQueueNotification {
        private Supplier<QueueManager.View> supplier;

        private PendingQueueNotification(Supplier<QueueManager.View> supplier) {
            this.supplier = supplier;
        }

        private void replace(Supplier<QueueManager.View> replacement) {
            supplier = replacement;
        }

        private Supplier<QueueManager.View> snapshotSupplier() {
            return supplier;
        }
    }
}
