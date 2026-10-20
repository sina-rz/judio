package io.github.sinaraz.internal.engine;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.internal.queue.QueueManager;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackEventBusTest {

    @Test
    void duplicateRegistrationDeliversOnceAndRemoveReallyRemoves() throws Exception {
        try (PlaybackEventBus bus = new PlaybackEventBus(Duration.ofSeconds(1))) {
            AtomicInteger count = new AtomicInteger();
            PlaybackListener listener = new PlaybackListener() {
                @Override
                public void onStateChanged(PlaybackSnapshot snapshot) {
                    count.incrementAndGet();
                }
            };
            bus.addListener(listener);
            bus.addListener(listener);   // documented: no extra effect
            bus.publish(l -> l.onStateChanged(null));
            Thread.sleep(300);
            assertEquals(1, count.get(), "same instance registered twice must be delivered once");

            bus.removeListener(listener);
            bus.publish(l -> l.onStateChanged(null));
            Thread.sleep(300);
            assertEquals(1, count.get(), "a removed listener must receive nothing more");
        }
    }

    @Test
    void coalescesConsecutiveQueueNotificationsBeforeMaterializingSnapshots() throws Exception {
        try (PlaybackEventBus bus = new PlaybackEventBus(Duration.ofSeconds(2))) {
            CountDownLatch listenerEntered = new CountDownLatch(1);
            CountDownLatch releaseListener = new CountDownLatch(1);
            CountDownLatch queueDelivered = new CountDownLatch(1);
            AtomicInteger queueCallbacks = new AtomicInteger();
            AtomicInteger snapshotCalls = new AtomicInteger();
            AtomicInteger deliveredSize = new AtomicInteger();
            bus.addListener(new PlaybackListener() {
                @Override
                public void onStateChanged(PlaybackSnapshot snapshot) {
                    listenerEntered.countDown();
                    await(releaseListener);
                }

                @Override
                public void onQueueChanged(List<io.github.sinaraz.model.Track> queue, QueueSnapshot state) {
                    queueCallbacks.incrementAndGet();
                    deliveredSize.set(state.size());
                    queueDelivered.countDown();
                }
            });

            bus.publish(listener -> listener.onStateChanged(null));
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS), "listener should block the event thread");
            for (int size = 1; size <= 100; size++) {
                int current = size;
                bus.publishQueueChanged(() -> {
                    snapshotCalls.incrementAndGet();
                    return view(current);
                });
            }
            assertEquals(0, snapshotCalls.get(), "backlogged updates must not each capture a full queue copy");

            releaseListener.countDown();
            assertTrue(queueDelivered.await(2, TimeUnit.SECONDS), "latest queue state should be delivered");
            assertEquals(1, queueCallbacks.get());
            assertEquals(100, deliveredSize.get());
            assertEquals(1, snapshotCalls.get());
        }
    }

    @Test
    void unrelatedEventsEndQueueCoalescingRunsAndPreservePublicationOrder() throws Exception {
        try (PlaybackEventBus bus = new PlaybackEventBus(Duration.ofSeconds(2))) {
            CountDownLatch listenerEntered = new CountDownLatch(1);
            CountDownLatch releaseListener = new CountDownLatch(1);
            CountDownLatch delivered = new CountDownLatch(3);
            List<String> order = new ArrayList<>();
            bus.addListener(new PlaybackListener() {
                @Override
                public void onStateChanged(PlaybackSnapshot snapshot) {
                    if (snapshot == null) {
                        listenerEntered.countDown();
                        await(releaseListener);
                    } else {
                        order.add("state");
                        delivered.countDown();
                    }
                }

                @Override
                public void onQueueChanged(List<io.github.sinaraz.model.Track> queue, QueueSnapshot state) {
                    order.add("queue-" + state.size());
                    delivered.countDown();
                }
            });

            bus.publish(listener -> listener.onStateChanged(null));
            assertTrue(listenerEntered.await(2, TimeUnit.SECONDS));
            bus.publishQueueChanged(() -> view(1));
            PlaybackSnapshot state = new PlaybackSnapshot(null, PlaybackState.READY, Duration.ZERO,
                    Duration.ZERO, 1.0, false, 1.0, 0.0, false, -1, 0, RepeatMode.OFF, false, null);
            bus.publish(listener -> listener.onStateChanged(state));
            bus.publishQueueChanged(() -> view(2));
            releaseListener.countDown();

            assertTrue(delivered.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("queue-1", "state", "queue-2"), order);
        }
    }

    private static QueueManager.View view(int size) {
        return new QueueManager.View(List.of(), new QueueSnapshot(-1, size, RepeatMode.OFF, false));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
