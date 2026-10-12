package io.github.sinaraz.internal.processing;

/** Converts interleaved float frames between mono and stereo. */
public final class ChannelMixer {


    /**
     * Writes {@code frames} stereo frames to {@code out}. Mono input is duplicated to both
     * channels; stereo input is copied unchanged.
     */
    public static void toStereo(float[] in, int frames, int channels, float[] out) {
        switch (channels) {
            case 1 -> {
                for (int i = 0; i < frames; i++) {
                    out[2 * i] = in[i];
                    out[2 * i + 1] = in[i];
                }
            }
            case 2 -> System.arraycopy(in, 0, out, 0, frames * 2);
            default -> throw new IllegalArgumentException("unsupported channel count: " + channels);
        }
    }

    /** Averages stereo to mono. Mono input is copied. */
    public static void toMono(float[] in, int frames, int channels, float[] out) {
        switch (channels) {
            case 1 -> System.arraycopy(in, 0, out, 0, frames);
            case 2 -> {
                for (int i = 0; i < frames; i++) {
                    out[i] = 0.5f * (in[2 * i] + in[2 * i + 1]);
                }
            }
            default -> throw new IllegalArgumentException("unsupported channel count: " + channels);
        }
    }
}
