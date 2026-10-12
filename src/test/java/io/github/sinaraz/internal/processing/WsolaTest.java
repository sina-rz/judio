package io.github.sinaraz.internal.processing;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WsolaTest {

    private static final int RATE = 44_100;

    private static float[] stereoSine(double freq, int frames) {
        float[] s = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            float v = (float) (0.5 * Math.sin(2 * Math.PI * freq * i / RATE));
            s[2 * i] = v;
            s[2 * i + 1] = v;
        }
        return s;
    }

    /** Feeds {@code in} through a fresh {@link Wsola} in blocks and returns the interleaved output. */
    private static float[] stretch(float[] in, int frames, double speed, int blockFrames) {
        Wsola wsola = new Wsola(RATE);
        float[] out = new float[2 * (int) (frames / speed + 8192)];
        int total = 0;
        for (int pos = 0; pos < frames; pos += blockFrames) {
            int n = Math.min(blockFrames, frames - pos);
            float[] block = Arrays.copyOfRange(in, 2 * pos, 2 * (pos + n));
            int produced = wsola.process(block, n, speed);
            System.arraycopy(wsola.output(), 0, out, 2 * total, 2 * produced);
            total += produced;
        }
        int tail = wsola.finish();
        System.arraycopy(wsola.output(), 0, out, 2 * total, 2 * tail);
        total += tail;
        return Arrays.copyOf(out, 2 * total);
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.5, 0.75, 1.0, 1.25, 1.5, 2.0})
    void outputLengthIsInputOverSpeed(double speed) {
        int frames = 2 * RATE + 123;
        float[] out = stretch(stereoSine(440, frames), frames, speed, 1000);
        assertEquals(Math.round(frames / speed), out.length / 2);
    }

    @Test
    void keepsPitchWhileChangingTempo() {
        int frames = 2 * RATE;
        double speed = 1.5;
        float[] out = stretch(stereoSine(440, frames), frames, speed, 512);

        // Measure the middle of the output, away from the start-up and flush edges.
        int from = out.length / 4;
        int to = 3 * out.length / 4;
        int crossings = 0;
        for (int i = from + 2; i < to; i += 2) {
            float prev = out[i - 2];
            float cur = out[i];
            if ((prev < 0 && cur >= 0) || (prev >= 0 && cur < 0)) {
                crossings++;
            }
        }
        double seconds = (to - from) / 2.0 / RATE;
        double frequency = crossings / (2.0 * seconds);

        // A resampler would sound at 660 Hz here (440 * 1.5); WSOLA must stay near 440 Hz.
        assertTrue(Math.abs(frequency - 440) < 440 * 0.03,
                "expected pitch near 440 Hz but measured " + frequency);
    }

    @Test
    void finishWithoutInputEmitsNothing() {
        Wsola wsola = new Wsola(RATE);
        assertEquals(0, wsola.finish());
    }

    @Test
    void rejectsNonPositiveSpeed() {
        Wsola wsola = new Wsola(RATE);
        assertThrows(IllegalArgumentException.class, () -> wsola.process(new float[2], 1, 0.0));
    }
}
