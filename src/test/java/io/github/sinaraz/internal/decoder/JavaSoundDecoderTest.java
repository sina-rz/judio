package io.github.sinaraz.internal.decoder;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.support.TestAudio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaSoundDecoderTest {

    @TempDir
    Path dir;

    private static byte[] readAll(AudioDecoder d) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = d.read(buf, 0, buf.length)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    void monoSixteenBitWavKeepsMonoAndExactLength() throws Exception {
        Path f = TestAudio.writeWav(dir.resolve("mono.wav"), 22050, 1, 22050, 16);
        try (AudioDecoder d = DecoderFactory.standard().open(f)) {
            AudioInfo info = d.audioInfo();
            assertEquals(1, info.channels());
            assertEquals(22050, info.sampleRate());
            assertEquals(16, info.sampleSizeBits());
            assertEquals(2, info.frameSize());
            assertEquals(22050, info.frameCount());
            assertEquals(Duration.ofSeconds(1), info.duration());
            assertEquals(22050 * 2, readAll(d).length);
        }
    }

    @Test
    void stereoTwentyFourBitIsConvertedToSixteenBitCanonicalPcm() throws Exception {
        Path f = TestAudio.writeWav(dir.resolve("s24.wav"), 4800, 2, 48000, 24);
        try (AudioDecoder d = DecoderFactory.standard().open(f)) {
            AudioInfo info = d.audioInfo();
            assertEquals(2, info.channels());
            assertEquals(16, info.sampleSizeBits());
            assertEquals(4, info.frameSize());
            byte[] pcm = readAll(d);
            assertEquals(4800 * 4, pcm.length);
            long energy = 0;
            for (int i = 0; i + 1 < pcm.length; i += 2) {
                short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                energy += (long) s * s;
            }
            assertTrue(energy > 0, "decoded tone must not be silent");
        }
    }

    @Test
    void aiffAndAuFilesDecode() throws Exception {
        Path aiff = TestAudio.writeAiff(dir.resolve("a.aiff"), 1000, 2, 44100, 16);
        Path au = TestAudio.writeAu(dir.resolve("a.au"), 1000, 1, 8000, 16);
        try (AudioDecoder d = DecoderFactory.standard().open(aiff)) {
            assertEquals(1000 * 4, readAll(d).length);
        }
        try (AudioDecoder d = DecoderFactory.standard().open(au)) {
            assertEquals(1000 * 2, readAll(d).length);
            assertEquals(8000, d.audioInfo().sampleRate());
        }
    }

    @Test
    void seekReturnsExactlyTheBytesAtThatFrame() throws Exception {
        Path f = TestAudio.writeWav(dir.resolve("seek.wav"), 44100, 2, 44100, 16);
        byte[] reference;
        try (AudioDecoder d = DecoderFactory.standard().open(f)) {
            reference = readAll(d);
        }
        try (AudioDecoder d = DecoderFactory.standard().open(f)) {
            assertTrue(d.isSeekable());
            d.seek(Duration.ofMillis(250));
            assertEquals(11025, d.framePosition());
            byte[] tail = readAll(d);
            assertArrayEquals(java.util.Arrays.copyOfRange(reference, 11025 * 4, reference.length), tail);
            assertEquals(44100, d.framePosition());
        }
    }

    @Test
    void seekPastEndLeavesEndOfStreamAndNegativeSeekClampsToStart() throws Exception {
        Path f = TestAudio.writeWav(dir.resolve("end.wav"), 1000, 1, 8000, 16);
        try (AudioDecoder d = DecoderFactory.standard().open(f)) {
            d.seek(Duration.ofSeconds(10));
            assertEquals(1000, d.framePosition());
            assertEquals(-1, d.read(new byte[64], 0, 64));
            d.seek(Duration.ofSeconds(-5));
            assertEquals(0, d.framePosition());
            assertEquals(2000, readAll(d).length);
        }
    }
}
