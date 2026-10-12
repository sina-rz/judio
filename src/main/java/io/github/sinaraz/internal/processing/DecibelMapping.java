package io.github.sinaraz.internal.processing;

/** Conversions between linear amplitude and decibels. */
public final class DecibelMapping {


    /**
     * Converts a linear amplitude to decibels: {@code 20 * log10(linear)}.
     * Zero (and anything non-positive) maps to negative infinity.
     */
    public static double linearToDb(double linear) {
        if (linear <= 0.0) {
            return Double.NEGATIVE_INFINITY;
        }
        return 20.0 * Math.log10(linear);
    }

    /** Inverse of {@link #linearToDb}. */
    public static double dbToLinear(double db) {
        return Math.pow(10.0, db / 20.0);
    }

    /**
     * Maps a linear volume to a hardware control value. Zero maps to the control minimum, and
     * out-of-range results are clamped to the control's supported range.
     */
    public static float toControlDb(double linear, float minDb, float maxDb) {
        if (linear <= 0.0) {
            return minDb;
        }
        double db = linearToDb(linear);
        return (float) Math.max(minDb, Math.min(maxDb, db));
    }
}
