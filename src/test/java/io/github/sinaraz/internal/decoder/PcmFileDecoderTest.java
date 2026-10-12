package io.github.sinaraz.internal.decoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.support.TestAudio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmFileDecoderTest {

    private static final int RATE = 8000;
    private static final int FRAMES = 6000;

    @TempDir
    Path dir;

    private Path write(String container, int bits, int channels) throws IOException {
        Path file = dir.resolve("tone-" + container + bits + "-" + channels + ".bin");
        return switch (container) {
            case "wav" -> TestAudio.writeWav(file, FRAMES, channels, RATE, bits);
            case "aiff" -> TestAudio.writeAiff(file, FRAMES, channels, RATE, bits);
            case "au" -> TestAudio.writeAu(file, FRAMES, channels, RATE, bits);
            default -> throw new IllegalArgumentException(container);
        };
    }

    private static byte[] readAll(AudioDecoder d) throws IOException {
        byte[] out = new byte[16 * 1024];
        java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = d.read(out, 0, out.length)) >= 0) {
            all.write(out, 0, n);
        }
        return all.toByteArray();
    }

    @ParameterizedTest(name = "{0} {1}-bit {2}ch matches Java Sound")
    @CsvSource({
            "wav,8,1", "wav,8,2", "wav,16,1", "wav,16,2", "wav,24,1", "wav,24,2", "wav,32,1", "wav,32,2",
            "aiff,8,2", "aiff,16,1", "aiff,16,2", "aiff,24,2", "aiff,32,1", "aiff,32,2",
            "au,8,2", "au,16,1", "au,16,2", "au,24,2", "au,32,1", "au,32,2"
    })
    void nativeReaderMatchesJavaSoundSampleForSample(String container, int bits, int channels) throws Exception {
        Path file = write(container, bits, channels);
        byte[] expected;
        try (AudioDecoder reference = new JavaSoundDecoder(file)) {
            expected = readAll(reference);
        }
        try (PcmFileDecoder pcm = new PcmFileDecoder(file)) {
            AudioInfo info = pcm.audioInfo();
            assertEquals(RATE, info.sampleRate());
            assertEquals(channels, info.channels());
            assertEquals(16, info.sampleSizeBits());
            assertEquals(FRAMES, info.frameCount());
            byte[] actual = readAll(pcm);
            assertEquals(expected.length, actual.length, "same number of bytes");
            // 16-bit is exact. 8-bit is a shift (Java Sound scales by 258): within one 8-bit step.
            // 24/32-bit are rounded here and truncated-or-rounded differently by Java Sound: within one LSB.
            int tolerance = bits == 16 ? 0 : bits == 8 ? 256 : 1;
            for (int i = 0; i < expected.length; i += 2) {
                int want = (short) ((expected[i] & 0xFF) | (expected[i + 1] << 8));
                int got = (short) ((actual[i] & 0xFF) | (actual[i + 1] << 8));
                assertTrue(Math.abs(want - got) <= tolerance,
                        "sample " + (i / 2) + ": java sound " + want + ", native " + got);
            }
        }
    }

    @Test
    void seekLandsOnTheSameSamplesAsSequentialDecode() throws Exception {
        Path file = write("wav", 24, 2);
        byte[] sequential;
        try (PcmFileDecoder d = new PcmFileDecoder(file)) {
            sequential = readAll(d);
        }
        int frameBytes = 4;
        Random random = new Random(7L);
        int[] targets = {0, 1, 17, 2999, 5999, FRAMES - 1, random.nextInt(FRAMES), random.nextInt(FRAMES)};
        try (PcmFileDecoder d = new PcmFileDecoder(file)) {
            for (int frame : targets) {
                d.seek(Duration.ofNanos(frame * 1_000_000_000L / RATE));
                assertEquals(frame, d.framePosition());
                byte[] tail = readAll(d);
                assertArrayEquals(Arrays.copyOfRange(sequential, frame * frameBytes, sequential.length), tail,
                        "seek to frame " + frame);
            }
        }
    }

    @Test
    void seekBeyondTheEndReachesEndOfStream() throws Exception {
        Path file = write("au", 16, 1);
        try (PcmFileDecoder d = new PcmFileDecoder(file)) {
            d.seek(Duration.ofHours(5));
            assertEquals(FRAMES, d.framePosition());
            assertEquals(-1, d.read(new byte[64], 0, 64));
        }
    }

    @Test
    void readReturnsWholeFramesOnly() throws Exception {
        Path file = write("wav", 16, 2);
        try (PcmFileDecoder d = new PcmFileDecoder(file)) {
            byte[] buffer = new byte[11];          // not a multiple of the 4-byte output frame
            int n = d.read(buffer, 0, buffer.length);
            assertEquals(8, n);
            assertEquals(2, d.framePosition());
        }
    }

    @Test
    void floatWaveFallsBackToJavaSound() throws Exception {
        Path file = dir.resolve("float.wav");
        byte[] floats = new byte[400 * 4];
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_FLOAT, RATE, 32, 1, 4, RATE, false);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(floats), format, 400)) {
            AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile());
        }
        assertThrows(UnsupportedAudioFormatException.class, () -> new PcmFileDecoder(file));
        try (AudioDecoder d = DecoderFactory.standard().open(file)) {
            assertEquals("JavaSound", d.audioInfo().decoderName());
        }
    }

    @Test
    void nativeReaderIsUsedForPlainPcm() throws Exception {
        Path file = write("aiff", 16, 2);
        try (AudioDecoder d = DecoderFactory.standard().open(file)) {
            assertInstanceOf(PcmFileDecoder.class, d);
        }
    }

    @Test
    void textFileIsRejectedByTheNativeReader() throws IOException {
        Path file = Files.writeString(dir.resolve("notes.wav"), "this is not audio at all, just text");
        assertThrows(UnsupportedAudioFormatException.class, () -> new PcmFileDecoder(file));
    }

    @Test
    void aiffSampleRateUses80BitExtendedFloat() {
        // 44100 Hz as an 80-bit extended float: exponent 0x400E, mantissa 0xAC44 << 48.
        byte[] raw = {0x40, 0x0E, (byte) 0xAC, 0x44, 0, 0, 0, 0, 0, 0};
        assertEquals(44100.0, PcmFileDecoder.extendedToDouble(java.nio.ByteBuffer.wrap(raw), 0), 1e-6);
    }
}
