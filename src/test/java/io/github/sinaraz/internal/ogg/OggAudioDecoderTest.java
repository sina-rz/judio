package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;

import javax.imageio.ImageIO;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.error.CorruptFileException;
import io.github.sinaraz.error.DecoderException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.internal.metadata.MetadataService;
import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.Track;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggAudioDecoderTest {

    @TempDir
    Path tempDir;

    private static Stream<Arguments> fixtures() {
        return Stream.concat(vorbisFixtures(), opusFixtures());
    }

    private static Stream<Arguments> vorbisFixtures() {
        return Stream.of(
                Arguments.of("vorbis-mono.ogg", 44_100, 1, 105_840L, "JOrbis"),
                Arguments.of("vorbis-stereo.ogg", 44_100, 2, 105_840L, "JOrbis"));
    }

    private static Stream<Arguments> opusFixtures() {
        return Stream.of(
                Arguments.of("opus-mono.ogg", 48_000, 1, 115_200L, "Concentus"),
                Arguments.of("opus-stereo.ogg", 48_000, 2, 115_200L, "Concentus"));
    }

    private static Stream<Arguments> vorbisOriginFixtures() {
        return Stream.of(
                Arguments.of("vorbis-mono.ogg", 44_100, 1),
                Arguments.of("vorbis-stereo.ogg", 44_100, 2));
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void localCodecFixturesDecodeToGranuleExactPcm(String name, int sampleRate, int channels,
                                                   long frames, String decoderName) throws Exception {
        Path file = fixture(name);
        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            assertEquals(sampleRate, decoder.audioInfo().sampleRate());
            assertEquals(channels, decoder.audioInfo().channels());
            assertEquals(16, decoder.audioInfo().sampleSizeBits());
            assertEquals(channels * 2, decoder.audioInfo().frameSize());
            assertEquals(frames, decoder.audioInfo().frameCount());
            assertEquals(Duration.ofMillis(2400), decoder.audioInfo().duration());
            assertTrue(decoder.audioInfo().seekable());
            assertTrue(decoder.isSeekable());
            assertEquals(decoderName, decoder.audioInfo().decoderName());

            byte[] pcm = readAll(decoder);
            assertEquals(frames * channels * 2L, pcm.length);
            assertEquals(frames, decoder.framePosition());
            assertTrue(maxAbsSample(pcm) > 1000, "fixture should decode to a non-silent tone");
            assertTrue(rms(pcm) > 500.0, "decoded tone should have measurable energy");
        }
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void localSeekingReproducesTheSequentialPcm(String name, int sampleRate, int channels,
                                                long frames, String decoderName) throws Exception {
        Path file = fixture(name);
        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            byte[] sequential = readAll(decoder);
            long targetFrame = sampleRate / 2L;
            decoder.seek(Duration.ofMillis(500));
            assertEquals(targetFrame, decoder.framePosition());

            int wanted = Math.min(8_192, sequential.length - Math.toIntExact(targetFrame * channels * 2L));
            byte[] actual = new byte[wanted];
            int got = decoder.read(actual, 0, actual.length);
            assertEquals(wanted, got);
            int start = Math.toIntExact(targetFrame * channels * 2L);
            assertTrue(Arrays.equals(Arrays.copyOfRange(sequential, start, start + wanted), actual),
                    "indexed seek must match sequential decoding");

            decoder.seek(Duration.ofSeconds(100));
            assertEquals(frames, decoder.framePosition());
            assertEquals(-1, decoder.read(actual, 0, actual.length));
        }
    }

    @Test
    void readsOpusAndVorbisCommentsThroughTheMetadataService() throws Exception {
        Path opus = fixture("opus-mono.ogg");
        Path vorbis = fixture("vorbis-mono.ogg");
        try (MetadataService service = new MetadataService()) {
            MetadataService.Result opusResult = service.loadAsync(Track.fromPath(opus)).get();
            MetadataService.Result vorbisResult = service.loadAsync(Track.fromPath(vorbis)).get();
            assertNull(opusResult.error());
            assertEquals("Judio test Opus mono", opusResult.metadata().title());
            assertEquals("Judio project", opusResult.metadata().artist());
            assertEquals(9, opusResult.metadata().trackNumber());
            assertNull(vorbisResult.error());
            assertEquals("Judio test Vorbis mono", vorbisResult.metadata().title());
            assertEquals("Judio project", vorbisResult.metadata().artist());
            assertEquals(7, vorbisResult.metadata().trackNumber());
        }
    }

    @Test
    void metadataServiceReadsEmbeddedOggCoverArt() throws Exception {
        BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", png));
        byte[] imageBytes = png.toByteArray();
        String encoded = Base64.getEncoder().encodeToString(imageBytes);
        byte[] tagged = OggTestPages.withAddedComment(resource("opus-mono.ogg"), "COVERART=" + encoded);
        tagged = OggTestPages.withAddedComment(tagged, "COVERARTMIME=image/png");
        Path file = Files.write(tempDir.resolve("opus-with-cover.ogg"), tagged);

        try (MetadataService service = new MetadataService()) {
            MetadataService.Result result = service.loadAsync(Track.fromPath(file)).get();
            assertNull(result.error());
            assertTrue(result.metadata().hasCoverArt());
            CoverArt cover = service.loadCoverArt(file).orElseThrow();
            assertEquals("image/png", cover.mimeType());
            assertArrayEquals(imageBytes, cover.data());
            assertTrue(ImageIO.read(new ByteArrayInputStream(cover.data())) != null);
        }
    }

    @Test
    void remoteDecoderIsIncrementalHasUnknownDurationAndCannotSeek() throws Exception {
        byte[] input = resource("opus-stereo.ogg");
        try (AudioDecoder decoder = OggAudioDecoder.openRemote(new ByteArrayInputStream(input), "test stream")) {
            assertEquals(48_000, decoder.audioInfo().sampleRate());
            assertEquals(2, decoder.audioInfo().channels());
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertFalse(decoder.isSeekable());
            assertThrows(SeekNotSupportedException.class, () -> decoder.seek(Duration.ofSeconds(1)));
            byte[] pcm = readAll(decoder);
            assertEquals(115_200L * 2 * 2, pcm.length);
            assertEquals(115_200, decoder.framePosition());
        }
    }

    @Test
    void chainsSameFormatOggLogicalStreamsAndReinitializesTheCodec() throws Exception {
        byte[] oneStream = resource("opus-mono.ogg");
        Path chained = Files.write(tempDir.resolve("chained.ogg"), OggTestPages.concat(oneStream, oneStream));
        try (AudioDecoder decoder = DecoderFactory.standard().open(chained)) {
            assertEquals(230_400, decoder.audioInfo().frameCount());
            assertEquals(Duration.ofMillis(4800), decoder.audioInfo().duration());
            byte[] pcm = readAll(decoder);
            assertEquals(230_400L * 2, pcm.length);
            assertEquals(230_400, decoder.framePosition());
        }
    }

    @Test
    void chainsSameFormatVorbisStreamsAndResetsTheJorbisState() throws Exception {
        byte[] oneStream = resource("vorbis-mono.ogg");
        Path chained = Files.write(tempDir.resolve("chained-vorbis.ogg"), OggTestPages.concat(oneStream, oneStream));
        try (AudioDecoder decoder = DecoderFactory.standard().open(chained)) {
            assertEquals(211_680, decoder.audioInfo().frameCount());
            assertEquals(Duration.ofMillis(4800), decoder.audioInfo().duration());
            byte[] pcm = readAll(decoder);
            assertEquals(211_680L * 2, pcm.length);
            assertEquals(211_680, decoder.framePosition());
            assertTrue(maxAbsSample(pcm) > 1000);
        }
    }

    @Test
    void chainedStreamsWithDifferentCanonicalFormatsAreRejectedExplicitly() throws Exception {
        byte[] mono = resource("vorbis-mono.ogg");
        byte[] stereo = resource("vorbis-stereo.ogg");
        Path chained = Files.write(tempDir.resolve("different-chain.ogg"), OggTestPages.concat(mono, stereo));
        assertThrows(UnsupportedAudioFormatException.class, () -> DecoderFactory.standard().open(chained));
    }

    @ParameterizedTest
    @MethodSource("opusFixtures")
    void opusNonZeroInitialGranuleOriginDoesNotChangePlayableSampleCount(String name, int sampleRate,
                                                                          int channels, long frames,
                                                                          String decoderName) throws Exception {
        byte[] shifted = OggTestPages.shiftPositiveGranules(resource(name), 4_096);
        Path file = Files.write(tempDir.resolve("shifted-" + name), shifted);
        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            assertEquals(decoderName, decoder.audioInfo().decoderName());
            assertEquals(frames, decoder.audioInfo().frameCount());
            byte[] pcm = readAll(decoder);
            assertEquals(frames * channels * 2L, pcm.length);
            assertTrue(maxAbsSample(pcm) > 1000);
        }
    }

    @ParameterizedTest
    @MethodSource("vorbisOriginFixtures")
    void validVorbisNonZeroOriginUsesTwoPacketFirstAudioPage(String name, int sampleRate,
                                                             int channels) throws Exception {
        OggTestPages.VorbisOrigin origin = OggTestPages.withValidVorbisInitialOrigin(resource(name), 4_096);
        Path file = Files.write(tempDir.resolve("vorbis-origin-" + name), origin.bytes());
        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            assertEquals(origin.decodedFrames(), decoder.audioInfo().frameCount());
            assertEquals(sampleRate, decoder.audioInfo().sampleRate());
            assertEquals(channels, decoder.audioInfo().channels());
            byte[] pcm = readAll(decoder);
            assertEquals(origin.decodedFrames() * channels * 2L, pcm.length);
            assertTrue(maxAbsSample(pcm) > 1000);
        }
    }

    @Test
    void rejectsVorbisNonZeroOriginWhenTheFirstAudioPageHasTooManyPackets() throws Exception {
        byte[] invalidOrigin = OggTestPages.shiftPositiveGranules(resource("vorbis-mono.ogg"), 4_096);
        Path file = Files.write(tempDir.resolve("invalid-vorbis-origin.ogg"), invalidOrigin);
        assertThrows(CorruptFileException.class, () -> DecoderFactory.standard().open(file));
    }

    @Test
    void completePagesWithoutAnEosMarkerRemainPlayableWithUnknownDuration() throws Exception {
        byte[] truncatedAtPageBoundary = OggTestPages.dropLastPage(resource("opus-mono.ogg"));
        Path file = Files.write(tempDir.resolve("no-eos.ogg"), truncatedAtPageBoundary);
        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertTrue(decoder.isSeekable());
            assertTrue(readAll(decoder).length > 0);
        }
    }

    @ParameterizedTest
    @MethodSource("vorbisFixtures")
    void pageBoundaryTruncationWithoutEosLeavesVorbisPlayableWithUnknownDuration(
            String name, int sampleRate, int channels, long frames, String decoderName) throws Exception {
        byte[] truncatedAtPageBoundary = OggTestPages.dropLastPage(resource(name));
        Path file = Files.write(tempDir.resolve("no-eos-" + name), truncatedAtPageBoundary);
        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            assertEquals(sampleRate, decoder.audioInfo().sampleRate());
            assertEquals(channels, decoder.audioInfo().channels());
            assertEquals(decoderName, decoder.audioInfo().decoderName());
            assertTrue(frames > 0);
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertTrue(decoder.isSeekable());
            assertTrue(readAll(decoder).length > 0);
        }
    }

    @Test
    void unsupportedOggCodecIsDistinguishedFromCorruption() throws Exception {
        byte[] unknown = OggTestPages.page(99, 0, 0x06, 0,
                List.of("not an audio codec".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        Path file = Files.write(tempDir.resolve("unknown.ogg"), unknown);
        assertThrows(UnsupportedAudioFormatException.class, () -> DecoderFactory.standard().open(file));
    }

    @Test
    void badChecksumAndTruncatedPhysicalPageAreReportedAsCorruption() throws Exception {
        byte[] valid = resource("opus-mono.ogg");
        byte[] badChecksum = valid.clone();
        badChecksum[badChecksum.length - 2] ^= 1;
        Path corrupt = Files.write(tempDir.resolve("bad-crc.ogg"), badChecksum);
        assertThrows(CorruptFileException.class, () -> DecoderFactory.standard().open(corrupt));

        Path truncated = Files.write(tempDir.resolve("truncated.ogg"), Arrays.copyOf(valid, valid.length - 1));
        assertThrows(CorruptFileException.class, () -> DecoderFactory.standard().open(truncated));
    }

    @Test
    void contentDetectionIgnoresTheFilenameExtension() throws Exception {
        Path wrongSuffix = Files.write(tempDir.resolve("renamed.mp3"), resource("vorbis-mono.ogg"));
        try (AudioDecoder decoder = DecoderFactory.standard().open(wrongSuffix)) {
            assertEquals("JOrbis", decoder.audioInfo().decoderName());
            assertEquals(105_840, decoder.audioInfo().frameCount());
        }
    }

    @Test
    void invalidRemoteInputIsClosedWhenOpeningFails() {
        byte[] invalid = OggTestPages.page(1, 0, 0x06, 0, List.of(new byte[] {1}));
        invalid[invalid.length - 1] ^= 1;
        TrackingInputStream input = new TrackingInputStream(invalid);
        assertThrows(CorruptFileException.class,
                () -> OggAudioDecoder.openRemote(input, "bad remote Ogg"));
        assertTrue(input.closed);
    }

    @Test
    void closeIsIdempotentAndFurtherReadsFailClearly() throws Exception {
        OggAudioDecoder decoder = OggAudioDecoder.openLocal(fixture("opus-mono.ogg"));
        decoder.close();
        decoder.close();
        assertThrows(DecoderException.class, () -> decoder.read(new byte[64], 0, 64));
    }

    private Path fixture(String name) throws IOException {
        return Files.write(tempDir.resolve(name), resource(name));
    }

    private byte[] resource(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/audio/ogg/" + name)) {
            if (in == null) {
                throw new IOException("missing Ogg test fixture: " + name);
            }
            return in.readAllBytes();
        }
    }

    private static byte[] readAll(AudioDecoder decoder) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int count;
        while ((count = decoder.read(buffer, 0, buffer.length)) >= 0) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static int maxAbsSample(byte[] pcm) {
        int maximum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
            maximum = Math.max(maximum, Math.abs(sample));
        }
        return maximum;
    }

    private static double rms(byte[] pcm) {
        double squared = 0;
        long count = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
            squared += (double) sample * sample;
            count++;
        }
        return Math.sqrt(squared / Math.max(1, count));
    }

    private static final class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        private TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }
}
