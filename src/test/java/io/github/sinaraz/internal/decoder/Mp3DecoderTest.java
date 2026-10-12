package io.github.sinaraz.internal.decoder;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.error.AudioPlayerException;
import io.github.sinaraz.model.AudioInfo;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * MP3 integration test. No MP3 file is committed to the repository, and no encoder is available
 * in the build, so the fixture must be provided explicitly:
 *
 * <pre>
 *   JUDIO_TEST_MP3=/absolute/path/to/sample.mp3 ./gradlew test
 * </pre>
 *
 * When the variable is unset the test is skipped with that instruction as the reason. When the
 * variable points at a missing file the test fails, because that is a configuration error.
 * Any MPEG-1 Layer III file works; a few-minute CC0 or self-recorded clip is enough.
 */
class Mp3DecoderTest {

    static final String FIXTURE_ENV = "JUDIO_TEST_MP3";

    @TempDir
    Path dir;

    private static Path configuredFixture() {
        String value = System.getenv(FIXTURE_ENV);
        Assumptions.assumeTrue(value != null && !value.isBlank(),
                "MP3 integration test skipped: set " + FIXTURE_ENV + " to an MP3 file to run it");
        Path p = Path.of(value);
        if (!Files.isRegularFile(p)) {
            fail(FIXTURE_ENV + " points to a missing file: " + p.toAbsolutePath());
        }
        return p;
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
    void fixtureDecodesAndSeekIsSampleAccurate() throws Exception {
        Path fixture = configuredFixture();
        try (AudioDecoder d = DecoderFactory.standard().open(fixture)) {
            AudioInfo info = d.audioInfo();
            assertTrue(info.duration().compareTo(Duration.ofSeconds(1)) > 0, "fixture must be longer than 1 s");
            assertEquals(4, info.frameSize());
            assertTrue(d.isSeekable());

            int frameSize = info.frameSize();
            int oneSecond = info.sampleRate() * frameSize;
            byte[] sequential = readFrames(d, 2 * oneSecond);
            assertEquals(2 * oneSecond, sequential.length);

            d.seek(Duration.ofSeconds(1));
            assertEquals(info.sampleRate(), d.framePosition());
            byte[] afterSeek = readFrames(d, oneSecond);
            assertArrayEquals(java.util.Arrays.copyOfRange(sequential, oneSecond, 2 * oneSecond), afterSeek,
                    "seeking to 1 s must reproduce the sequential decode exactly");
        }
    }

    /** Seeks to several frame-exact positions, including ones inside the first frames and near the end. */
    @Test
    void fixtureSeeksAtManyPositionsMatchSequentialDecode() throws Exception {
        Path fixture = configuredFixture();
        try (AudioDecoder d = DecoderFactory.standard().open(fixture)) {
            AudioInfo info = d.audioInfo();
            int frameSize = info.frameSize();
            long total = info.frameCount();
            byte[] everything = readFrames(d, (int) Math.min(Integer.MAX_VALUE / 2, total * frameSize));
            // Fixed targets around frame boundaries, plus a deterministic spread across the file.
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
                assertArrayEquals(java.util.Arrays.copyOfRange(everything, (int) (frame * frameSize),
                        (int) (frame * frameSize) + want), got, "seek to frame " + frame);
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
}
