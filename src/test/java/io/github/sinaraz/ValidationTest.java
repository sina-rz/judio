package io.github.sinaraz;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.audio.StreamOptions;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Boundary validation for the two public configuration records. */
class ValidationTest {

    @Test
    void playbackConfigRejectsInfiniteSpeeds() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackConfig.builder().maxSpeed(Double.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackConfig.builder()
                        .minSpeed(Double.POSITIVE_INFINITY).maxSpeed(Double.POSITIVE_INFINITY).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackConfig.builder().minSpeed(Double.NaN).build());
    }

    @Test
    void playbackConfigBoundsAllocations() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackConfig.builder().outputBufferBytes(Integer.MAX_VALUE).build());
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackConfig.builder().chunkBytes(PlaybackConfig.MAX_CHUNK_BYTES + 4).build());
        // Values at the documented bounds are accepted.
        PlaybackConfig.builder()
                .outputBufferBytes(PlaybackConfig.MAX_OUTPUT_BUFFER_BYTES)
                .chunkBytes(PlaybackConfig.MAX_CHUNK_BYTES)
                .build();
    }

    @Test
    void streamOptionsBoundBufferAndRetries() {
        assertThrows(IllegalArgumentException.class,
                () -> StreamOptions.builder().bufferBytes(StreamOptions.MAX_BUFFER_BYTES + 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> StreamOptions.builder().maxRetries(StreamOptions.MAX_RETRIES_LIMIT + 1).build());
        assertThrows(IllegalArgumentException.class,
                () -> StreamOptions.builder().bufferBytes(StreamOptions.MIN_BUFFER_BYTES - 1).build());
        StreamOptions options = StreamOptions.builder()
                .bufferBytes(StreamOptions.MAX_BUFFER_BYTES)
                .maxRetries(StreamOptions.MAX_RETRIES_LIMIT)
                .build();
        assertTrue(options.bufferBytes() > 0);
    }

    @Test
    void defaultsRemainValid() {
        PlaybackConfig.defaults();
        StreamOptions.defaults();
        Duration.ofMillis(1); // keep Duration import honest if defaults change
    }
}
