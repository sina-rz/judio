package io.github.sinaraz.internal.engine;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.PlaybackSnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
