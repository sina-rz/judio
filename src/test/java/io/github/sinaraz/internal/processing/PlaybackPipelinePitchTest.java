package io.github.sinaraz.internal.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackPipelinePitchTest {

    private static final int RATE = 44_100;

    /** Mono PCM16 little-endian ramp, so the output is clearly non-silent. */
    private static byte[] mono(int frames) {
        byte[] pcm = new byte[frames * 2];
        for (int i = 0; i < frames; i++) {
            short v = (short) ((i % 200) * 100 - 10_000);
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        return pcm;
    }

    @Test
    void pitchPreservingPathEmitsOneStereoFramePerOutputFrame() {
        PlaybackPipeline pipeline = new PlaybackPipeline();
        pipeline.prepare(RATE);
        int frames = RATE;
        double speed = 1.25;
        byte[] pcm = mono(frames);

        long emitted = 0;
        for (int pos = 0; pos < frames; pos += 4096) {
            int n = Math.min(4096, frames - pos);
            byte[] block = new byte[n * 2];
            System.arraycopy(pcm, pos * 2, block, 0, n * 2);
            emitted += pipeline.process(block, block.length, 1, speed, true, 1.0f, 0.0f) / 4;
        }
        emitted += pipeline.finish(1.0f, 0.0f) / 4;

        assertEquals(Math.round(frames / speed), emitted);
    }

    @Test
    void resamplingPathNeedsNoPrepare() {
        PlaybackPipeline pipeline = new PlaybackPipeline();
        int produced = pipeline.process(mono(1024), 2048, 1, 1.0, false, 1.0f, 0.0f);
        // The interpolator holds back its last frame until finish(), so count both calls.
        produced += pipeline.finish(1.0f, 0.0f);
        assertEquals(1024 * 4, produced);
    }

    @Test
    void pitchPreservingPathRequiresPrepare() {
        PlaybackPipeline pipeline = new PlaybackPipeline();
        assertThrows(IllegalStateException.class,
                () -> pipeline.process(mono(64), 128, 1, 1.0, true, 1.0f, 0.0f));
    }

    @Test
    void latencyIsZeroWhileResampling() {
        PlaybackPipeline pipeline = new PlaybackPipeline();
        pipeline.prepare(RATE);
        pipeline.process(mono(2048), 4096, 1, 1.0, false, 1.0f, 0.0f);
        assertEquals(0L, pipeline.latencyFrames());
    }
}
