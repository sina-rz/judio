package io.github.sinaraz.internal.processing;

/**
 * Stereo panning with the constant-power (sin/cos) law.
 *
 * <p>{@code pan = -1} is full left, {@code 0} is centre and {@code +1} is full right. With
 * this law the summed power is constant across the range, so a centred source is 3 dB
 * quieter per channel than a hard-panned one. The engine bypasses this processor entirely
 * when pan is exactly zero, so the default output level is unchanged.
 */
public final class PanProcessor {


    /** Applies pan to the first {@code frames} stereo frames in place. */
    public static void apply(float[] stereo, int frames, float pan) {
        if (Float.isNaN(pan)) {
            throw new IllegalArgumentException("pan must not be NaN");
        }
        double p = Math.max(-1.0, Math.min(1.0, pan));
        double theta = (p + 1.0) * Math.PI / 4.0;
        float left = (float) Math.cos(theta);
        float right = (float) Math.sin(theta);
        for (int i = 0; i < frames; i++) {
            stereo[2 * i] *= left;
            stereo[2 * i + 1] *= right;
        }
    }

    /** Gain applied to the left channel for a pan value. Exposed for tests and diagnostics. */
    public static double leftGain(double pan) {
        double p = Math.max(-1.0, Math.min(1.0, pan));
        return Math.cos((p + 1.0) * Math.PI / 4.0);
    }

    /** Gain applied to the right channel for a pan value. */
    public static double rightGain(double pan) {
        double p = Math.max(-1.0, Math.min(1.0, pan));
        return Math.sin((p + 1.0) * Math.PI / 4.0);
    }
}
