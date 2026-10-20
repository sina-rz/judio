package io.github.sinaraz;

import java.time.Duration;

/**
 * Tunable playback parameters.
 *
 * @param outputBufferBytes        size of the {@code SourceDataLine} buffer. Larger values tolerate
 *                                 scheduling jitter; smaller values reduce pause/seek latency.
 * @param chunkBytes               bytes requested from the decoder per render step; must be a
 *                                 positive multiple of 4 (stereo 16-bit frame size).
 * @param progressInterval         minimum time between progress events.
 * @param minSpeed                 lowest allowed playback speed factor.
 * @param maxSpeed                 highest allowed playback speed factor.
 * @param previousRestartThreshold if the position exceeds this value, "previous" restarts the
 *                                 current track instead of going to the previous one.
 * @param autoAdvanceOnError       whether a failed track is skipped to the next queue item.
 * @param closeTimeout             shared end-to-end deadline for background-worker waits during
 *                                 {@code close()}; lock acquisition and synchronous cleanup may extend total call time.
 */
public record PlaybackConfig(
        int outputBufferBytes,
        int chunkBytes,
        Duration progressInterval,
        double minSpeed,
        double maxSpeed,
        Duration previousRestartThreshold,
        boolean autoAdvanceOnError,
        Duration closeTimeout) {

    /** Largest accepted output buffer (64 MiB): bounds a single allocation. */
    public static final int MAX_OUTPUT_BUFFER_BYTES = 64 * 1024 * 1024;
    /** Largest accepted decode chunk (1 MiB): bounds per-chunk allocations. */
    public static final int MAX_CHUNK_BYTES = 1024 * 1024;

    /**
     * Validates the values.
     *
     * @throws IllegalArgumentException if a value is out of range
     */
    public PlaybackConfig {
        if (outputBufferBytes < 4096 || outputBufferBytes > MAX_OUTPUT_BUFFER_BYTES) {
            throw new IllegalArgumentException(
                    "outputBufferBytes must be in [4096, " + MAX_OUTPUT_BUFFER_BYTES + "]");
        }
        if (chunkBytes <= 0 || chunkBytes % 4 != 0 || chunkBytes > MAX_CHUNK_BYTES) {
            throw new IllegalArgumentException(
                    "chunkBytes must be a positive multiple of 4 up to " + MAX_CHUNK_BYTES);
        }
        if (progressInterval == null || progressInterval.isNegative() || progressInterval.isZero()) {
            throw new IllegalArgumentException("progressInterval must be positive");
        }
        if (!Double.isFinite(minSpeed) || !Double.isFinite(maxSpeed)
                || !(minSpeed > 0.0) || !(maxSpeed >= minSpeed)) {
            throw new IllegalArgumentException(
                    "speed range must satisfy 0 < minSpeed <= maxSpeed with finite values");
        }
        if (previousRestartThreshold == null || previousRestartThreshold.isNegative()) {
            throw new IllegalArgumentException("previousRestartThreshold must not be negative");
        }
        if (closeTimeout == null || closeTimeout.isNegative()) {
            throw new IllegalArgumentException("closeTimeout must not be negative");
        }
    }

    /**
     * Returns the default configuration: 64 KiB output buffer, 4 KiB chunks, progress every 100 ms,
     * speed between 0.5x and 2.0x, a 3-second previous-track threshold, auto-advance on error, and
     * a 3-second close timeout.
     *
     * @return the default configuration
     */
    public static PlaybackConfig defaults() {
        return new PlaybackConfig(
                65_536,
                4_096,
                Duration.ofMillis(100),
                0.5,
                2.0,
                Duration.ofSeconds(3),
                true,
                Duration.ofSeconds(3));
    }

    /**
     * Returns a builder that starts from the defaults. Only the settings you change need a call.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link PlaybackConfig}. Start from {@link PlaybackConfig#defaults()} values. */
    public static final class Builder {

        private final PlaybackConfig base = defaults();
        private int outputBufferBytes = base.outputBufferBytes();
        private int chunkBytes = base.chunkBytes();
        private Duration progressInterval = base.progressInterval();
        private double minSpeed = base.minSpeed();
        private double maxSpeed = base.maxSpeed();
        private Duration previousRestartThreshold = base.previousRestartThreshold();
        private boolean autoAdvanceOnError = base.autoAdvanceOnError();
        private Duration closeTimeout = base.closeTimeout();

        private Builder() {}

        /**
         * Sets {@link PlaybackConfig#outputBufferBytes()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder outputBufferBytes(int value) {
            this.outputBufferBytes = value;
            return this;
        }

        /**
         * Sets {@link PlaybackConfig#chunkBytes()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder chunkBytes(int value) {
            this.chunkBytes = value;
            return this;
        }

        /**
         * Sets {@link PlaybackConfig#progressInterval()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder progressInterval(Duration value) {
            this.progressInterval = value;
            return this;
        }

        /**
         * Sets {@link PlaybackConfig#minSpeed()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder minSpeed(double value) {
            this.minSpeed = value;
            return this;
        }

        /**
         * Sets {@link PlaybackConfig#maxSpeed()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder maxSpeed(double value) {
            this.maxSpeed = value;
            return this;
        }

        /**
         * Sets {@link PlaybackConfig#previousRestartThreshold()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder previousRestartThreshold(Duration value) {
            this.previousRestartThreshold = value;
            return this;
        }

        /**
         * Sets {@link PlaybackConfig#autoAdvanceOnError()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder autoAdvanceOnError(boolean value) {
            this.autoAdvanceOnError = value;
            return this;
        }

        /**
         * Sets {@link PlaybackConfig#closeTimeout()}.
         *
         * @param value the new value
         * @return this builder
         */
        public Builder closeTimeout(Duration value) {
            this.closeTimeout = value;
            return this;
        }

        /**
         * Validates the values and creates the configuration.
         *
         * @return the configuration
         * @throws IllegalArgumentException if a value is out of range
         */
        public PlaybackConfig build() {
            return new PlaybackConfig(outputBufferBytes, chunkBytes, progressInterval, minSpeed, maxSpeed,
                    previousRestartThreshold, autoAdvanceOnError, closeTimeout);
        }
    }
}
