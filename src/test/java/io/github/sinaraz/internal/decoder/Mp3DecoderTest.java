package io.github.sinaraz.internal.decoder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.error.AudioPlayerException;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.support.TestMp3;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MP3 coverage uses a generated silent MPEG-1 Layer III stream and a committed
 * CC0 FFmpeg-encoded MPEG Layer II fixture. The Layer III fixture has zeroed
 * main data, so its PCM content assertions are vacuous; the Layer II fixture
 * exercises non-silent decoded PCM and sample-accurate seeking. Set
 * {@code JUDIO_TEST_MP3} to additionally exercise another external MPEG audio file
 * (see {@link #realFileAlsoDecodesIfConfigured()}).
 */
class Mp3DecoderTest {

    @TempDir
    Path dir;

    private Path silentFixture(int frames) throws Exception {
        return TestMp3.writeSilentMp3(dir.resolve("silent.mp3"), frames);
    }

    private static byte[] readFrames(AudioDecoder d, int bytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        while (out.size() < bytes) {
            int n = d.read(buf, 0, Math.min(buf.length, bytes - out.size()));
            if (n < 0) {
                break;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    @Test
    void generatedMp3DecodesAndSeekIsSampleAccurate() throws Exception {
        Path fixture = silentFixture(300); // 300 * 1152 / 44100 = 7.83 s
        try (AudioDecoder d = DecoderFactory.standard().open(fixture)) {
            AudioInfo info = d.audioInfo();
            assertEquals(TestMp3.SAMPLE_RATE, info.sampleRate());
            assertEquals(TestMp3.CHANNELS, info.channels());
            assertEquals(300L * TestMp3.SAMPLES_PER_FRAME, info.frameCount());
            assertEquals(4, info.frameSize());
            assertTrue(d.isSeekable());

            int frameSize = info.frameSize();
            int oneSecond = info.sampleRate() * frameSize;
            byte[] sequential = readFrames(d, 2 * oneSecond);
            assertEquals(2 * oneSecond, sequential.length);

            d.seek(Duration.ofSeconds(1));
            assertEquals(info.sampleRate(), d.framePosition());
            byte[] afterSeek = readFrames(d, oneSecond);
            assertEquals(oneSecond, afterSeek.length);
            assertArrayEquals(java.util.Arrays.copyOfRange(sequential, oneSecond, 2 * oneSecond), afterSeek,
                    "seeking to 1 s must reproduce the sequential decode exactly");
        }
    }

    @Test
    void realMpegLayerTwoFixtureDecodesAndSeeksAccurately() throws Exception {
        Path fixture = dir.resolve("layer2.mp2");
        try (InputStream in = getClass().getResourceAsStream("/audio/mp2-layer2-44100-stereo.mp2")) {
            if (in == null) {
                throw new java.io.IOException("missing MPEG Layer II test fixture");
            }
            Files.copy(in, fixture);
        }
        try (AudioDecoder decoder = DecoderFactory.standard().open(fixture)) {
            AudioInfo info = decoder.audioInfo();
            assertEquals(44_100, info.sampleRate());
            assertEquals(2, info.channels());
            assertEquals(105_984, info.frameCount());
            assertEquals("JLayer", info.decoderName());
            byte[] sequential = readFrames(decoder, Math.toIntExact(info.frameCount() * info.frameSize()));
            assertEquals(105_984 * 4, sequential.length);
            assertTrue(maxAbsSample(sequential) > 500);

            int targetBytes = info.sampleRate() * info.frameSize();
            decoder.seek(Duration.ofSeconds(1));
            assertEquals(info.sampleRate(), decoder.framePosition());
            byte[] afterSeek = readFrames(decoder, 8192);
            assertArrayEquals(java.util.Arrays.copyOfRange(sequential, targetBytes, targetBytes + afterSeek.length),
                    afterSeek, "MPEG Layer II seeking must reproduce sequential PCM");
        }
    }

    /** Seeks to several frame-exact positions, including ones inside the first frames and near the end. */
    @Test
    void generatedMp3SeeksAtManyPositions() throws Exception {
        Path fixture = silentFixture(300);
        try (AudioDecoder d = DecoderFactory.standard().open(fixture)) {
            AudioInfo info = d.audioInfo();
            int frameSize = info.frameSize();
            long total = info.frameCount();
            byte[] everything = readFrames(d, (int) (total * frameSize));
            assertEquals(total * frameSize, everything.length);
            java.util.Random random = new java.util.Random(11L);
            long[] targets = new long[40];
            long[] fixed = {1, 575, 576, 1151, 1152, 2303, 2304, 5000, total / 3, total / 2 + 7, total - 2000};
            System.arraycopy(fixed, 0, targets, 0, fixed.length);
            for (int i = fixed.length; i < targets.length; i++) {
                targets[i] = 1 + (long) (random.nextDouble() * (total - 4001));
            }
            for (long frame : targets) {
                d.seek(Duration.ofNanos(Math.round(frame * 1_000_000_000.0 / info.sampleRate())));
                assertEquals(frame, d.framePosition(), "framePosition after seek to frame " + frame);
                int want = (int) Math.min(4000L * frameSize, everything.length - frame * frameSize);
                byte[] got = readFrames(d, want);
                assertEquals(want, got.length, "bytes after seek to frame " + frame);
            }
        }
    }

    @Test
    void corruptMp3IsReportedNotSilentlyAccepted() throws Exception {
        byte[] bogus = new byte[4096];
        bogus[0] = 'I';
        bogus[1] = 'D';
        bogus[2] = '3';
        Path f = Files.write(dir.resolve("bogus.mp3"), bogus);
        assertThrows(AudioPlayerException.class, () -> DecoderFactory.standard().open(f));
    }

    private static int maxAbsSample(byte[] pcm) {
        int maximum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
            maximum = Math.max(maximum, Math.abs(sample));
        }
        return maximum;
    }

    /** Optional extra: a real encoder-produced MP3 file, only when explicitly configured. */
    @Test
    void realFileAlsoDecodesIfConfigured() throws Exception {
        String value = System.getenv("JUDIO_TEST_MP3");
        org.junit.jupiter.api.Assumptions.assumeTrue(value != null && !value.isBlank(),
                "optional: set JUDIO_TEST_MP3 to also test a real-world MP3 file");
        Path fixture = Path.of(value);
        if (!Files.isRegularFile(fixture)) {
            org.junit.jupiter.api.Assertions.fail("JUDIO_TEST_MP3 points to a missing file: " + fixture);
        }
        try (AudioDecoder d = DecoderFactory.standard().open(fixture)) {
            assertTrue(d.audioInfo().duration().compareTo(Duration.ofSeconds(1)) > 0);
            assertTrue(readFrames(d, 8192).length > 0);
        }
    }
}
