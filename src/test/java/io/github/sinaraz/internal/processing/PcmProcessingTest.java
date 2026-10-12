package io.github.sinaraz.internal.processing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmProcessingTest {

    private static short shortAt(byte[] b, int i) {
        return (short) ((b[i] & 0xFF) | (b[i + 1] << 8));
    }

    @Test
    void sixteenBitLittleEndianDecodesToSignedFloat() {
        byte[] src = {(byte) 0x00, (byte) 0x80, (byte) 0xFF, (byte) 0x7F}; // -32768, 32767
        float[] dst = new float[2];
        assertEquals(2, PcmCodec.toFloat16LE(src, 0, 4, dst, 0));
        assertEquals(-1.0f, dst[0], 1e-6f);
        assertEquals(32767f / 32768f, dst[1], 1e-6f);
    }

    @Test
    void twentyFourBitBigEndianAndUnsignedEightBitAreNormalised() {
        float[] dst = new float[4];
        byte[] be24 = {0x40, 0x00, 0x00}; // +0.5 in 24-bit big-endian signed
        assertEquals(1, PcmCodec.toFloat(be24, 0, 3, 24, true, true, dst, 0));
        assertEquals(0.5f, dst[0], 1e-6f);

        byte[] u8 = {(byte) 128, 0, (byte) 255};
        assertEquals(3, PcmCodec.toFloat(u8, 0, 3, 8, false, false, dst, 0));
        assertEquals(0.0f, dst[0], 1e-6f);
        assertEquals(-1.0f, dst[1], 1e-6f);
    }

    @Test
    void floatToSixteenBitSaturatesInsteadOfWrapping() {
        float[] src = {2.0f, -2.0f, 0.0f, 0.5f};
        byte[] out = new byte[8];
        PcmCodec.fromFloat16LE(src, 4, out, 0);
        assertEquals(32767, shortAt(out, 0));
        assertEquals(-32768, shortAt(out, 2));
        assertEquals(0, shortAt(out, 4));
        assertEquals(16384, shortAt(out, 6));
    }

    @Test
    void roundTripPreservesSixteenBitValues() {
        byte[] original = new byte[2 * 100];
        for (int i = 0; i < original.length; i++) {
            original[i] = (byte) (i * 37);
        }
        float[] f = new float[100];
        PcmCodec.toFloat16LE(original, 0, original.length, f, 0);
        byte[] back = new byte[original.length];
        PcmCodec.fromFloat16LE(f, 100, back, 0);
        assertArrayEquals(original, back);
    }

    @Test
    void unsupportedBitDepthIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PcmCodec.toFloat(new byte[4], 0, 4, 12, false, true, new float[2], 0));
    }

    @Test
    void volumeScalesAndZeroSilences() {
        float[] s = {1.0f, -0.5f, 0.25f};
        VolumeProcessor.apply(s, 3, 0.5f);
        assertEquals(0.5f, s[0], 1e-6f);
        assertEquals(-0.25f, s[1], 1e-6f);
        VolumeProcessor.apply(s, 3, 0.0f);
        assertEquals(0.0f, s[0]);
        assertEquals(0.0f, s[2]);
    }

    @Test
    void decibelMappingRoundTripsAndHandlesSilence() {
        assertEquals(0.0, DecibelMapping.linearToDb(1.0), 1e-9);
        assertEquals(-6.0206, DecibelMapping.linearToDb(0.5), 1e-3);
        assertEquals(Double.NEGATIVE_INFINITY, DecibelMapping.linearToDb(0.0));
        assertEquals(0.25, DecibelMapping.dbToLinear(DecibelMapping.linearToDb(0.25)), 1e-9);
        assertEquals(-60f, DecibelMapping.toControlDb(0.0, -60f, 6f));
        assertEquals(6f, DecibelMapping.toControlDb(10.0, -60f, 6f));
    }

    @Test
    void panKeepsCentreEvenAndSteersWithExtremes() {
        assertEquals(1.0, PanProcessor.leftGain(-1.0), 1e-9);
        assertEquals(0.0, PanProcessor.rightGain(-1.0), 1e-9);
        assertEquals(PanProcessor.leftGain(0.0), PanProcessor.rightGain(0.0), 1e-9);

        float[] stereo = {1f, 1f, 1f, 1f};
        PanProcessor.apply(stereo, 2, 1.0f);
        assertEquals(0.0f, stereo[0], 1e-6f, "hard right silences the left channel");
        assertEquals(1.0f, stereo[1], 1e-6f);
    }

    @Test
    void channelMixerDuplicatesMonoAndAveragesStereo() {
        float[] mono = {0.2f, -0.4f};
        float[] stereo = new float[4];
        ChannelMixer.toStereo(mono, 2, 1, stereo);
        assertArrayEquals(new float[] {0.2f, 0.2f, -0.4f, -0.4f}, stereo, 1e-6f);

        float[] back = new float[2];
        ChannelMixer.toMono(new float[] {1f, 0f, 0f, 1f}, 2, 2, back);
        assertArrayEquals(new float[] {0.5f, 0.5f}, back, 1e-6f);
    }

    /** Runs the processor over {@code ramp} in blocks of {@code block} frames and collects all output. */
    private static float[] resampleInBlocks(float[] ramp, int frames, double speed, int block, int[] outFrames) {
        SpeedProcessor sp = new SpeedProcessor();
        sp.setSpeed(speed);
        float[] collected = new float[(int) (frames / speed) * 2 + 64];
        int total = 0;
        for (int start = 0; start < frames; start += block) {
            int n = Math.min(block, frames - start);
            float[] in = new float[n * 2];
            System.arraycopy(ramp, start * 2, in, 0, n * 2);
            float[] out = new float[sp.maxOutputFrames(n) * 2];
            int k = sp.process(in, n, out);
            System.arraycopy(out, 0, collected, total * 2, k * 2);
            total += k;
        }
        float[] tail = new float[4];
        int k = sp.finish(tail);
        System.arraycopy(tail, 0, collected, total * 2, k * 2);
        total += k;
        outFrames[0] = total;
        return collected;
    }

    @Test
    void speedOneIsIdentityAcrossChunkBoundaries() {
        int frames = 1000;
        float[] ramp = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            ramp[2 * i] = i;
            ramp[2 * i + 1] = -i;
        }
        int[] whole = new int[1];
        int[] chunked = new int[1];
        float[] a = resampleInBlocks(ramp, frames, 1.0, frames, whole);
        float[] b = resampleInBlocks(ramp, frames, 1.0, 97, chunked);

        assertEquals(frames, whole[0], "speed 1.0 preserves the frame count exactly");
        assertEquals(whole[0], chunked[0], "frame count does not depend on chunking");
        for (int i = 0; i < frames; i++) {
            assertEquals(i, a[2 * i], 1e-3f, "frame " + i + " (single block)");
            assertEquals(a[2 * i], b[2 * i], 1e-6f, "frame " + i + " (chunked)");
            assertEquals(-i, a[2 * i + 1], 1e-3f, "right channel at frame " + i);
        }
    }

    @Test
    void doubleSpeedHalvesLengthAndKeepsLinearContinuity() {
        int frames = 1001;
        float[] ramp = new float[frames * 2];
        for (int i = 0; i < frames; i++) {
            ramp[2 * i] = i / 1000f;
            ramp[2 * i + 1] = i / 1000f;
        }
        int[] count = new int[1];
        float[] out = resampleInBlocks(ramp, frames, 2.0, 333, count);
        assertEquals(frames / 2.0, count[0], 2.0, "output length is about input / speed");
        for (int i = 0; i < 400; i++) {
            assertEquals((2f * i) / 1000f, out[2 * i], 1e-3f, "linear ramp preserved at speed 2, frame " + i);
        }
    }

    @Test
    void pipelineHoldsBackOneFrameThenFlushesItAndAppliesGain() {
        byte[] pcm = new byte[4 * 10];
        for (int i = 0; i < 10; i++) {
            pcm[4 * i + 1] = 0x40; // 16384 left
            pcm[4 * i + 3] = 0x40; // 16384 right
        }
        PlaybackPipeline p = new PlaybackPipeline();
        int bodyLength = p.process(pcm, pcm.length, 2, 1.0, false, 0.5f, 0.0f);
        byte[] body = java.util.Arrays.copyOf(p.output(), bodyLength);
        int tailLength = p.finish(0.5f, 0.0f);
        byte[] tail = java.util.Arrays.copyOf(p.output(), tailLength);
        assertEquals(pcm.length - 4, body.length, "one frame is held back until the stream ends");
        assertEquals(4, tail.length);
        assertTrue(Math.abs(shortAt(body, 0) - 8192) <= 2, "gain 0.5 halves 16384, got " + shortAt(body, 0));
        assertTrue(Math.abs(shortAt(tail, 0) - 8192) <= 2);
    }

    @Test
    void pipelineMonoInputIsDuplicatedToStereo() {
        byte[] mono = new byte[2 * 4];
        for (int i = 0; i < 4; i++) {
            mono[2 * i + 1] = 0x20;
        }
        PlaybackPipeline p = new PlaybackPipeline();
        int outLength = p.process(mono, mono.length, 1, 1.0, false, 1.0f, 0.0f);
        byte[] out = java.util.Arrays.copyOf(p.output(), outLength);
        assertEquals(3 * 4, out.length, "mono frames become stereo frames, one held back");
        for (int i = 0; i + 3 < out.length; i += 4) {
            assertEquals(out[i], out[i + 2]);
            assertEquals(out[i + 1], out[i + 3]);
        }
        assertFalse(shortAt(out, 0) == 0);
    }
}
