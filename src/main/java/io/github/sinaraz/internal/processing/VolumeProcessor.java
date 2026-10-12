package io.github.sinaraz.internal.processing;

/**
 * Software gain. Gain is a linear amplitude factor in {@code [0, 1]}, so scaling never
 * increases the magnitude of a sample; the final saturation happens in {@link PcmCodec}.
 */
public final class VolumeProcessor {


    /** Multiplies the first {@code count} samples in place by {@code gain} (clamped to [0, 1]). */
    public static void apply(float[] samples, int count, float gain) {
        float g = clamp01(gain);
        if (g == 1.0f) {
            return;
        }
        for (int i = 0; i < count; i++) {
            samples[i] *= g;
        }
    }

    static float clamp01(float v) {
        if (Float.isNaN(v)) {
            throw new IllegalArgumentException("gain must not be NaN");
        }
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}
