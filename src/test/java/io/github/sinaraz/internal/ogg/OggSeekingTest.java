package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.sinaraz.audio.AudioDecoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real-codec regression tests for indexed seeks, bounded metadata I/O and stream ownership. */
class OggSeekingTest {

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "opus-stereo.ogg", "vorbis-mono.ogg", "vorbis-stereo.ogg"})
    void indexedLocalSeeksLandOnExactFramesInBothDirections(String name) throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource(name), 10, 3);
        Path file = Files.write(tempDir.resolve(name), audio.bytes());
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            byte[] reference = readAll(decoder);
            assertEquals(audio.frames() * audio.channels() * 2, reference.length);
            for (long millis : new long[] {19_237, 3_011, 17_503, 900, 0, 23_000}) {
                decoder.seek(Duration.ofMillis(millis));
                long target = Math.round(millis * audio.sampleRate() / 1000.0);
                assertEquals(target, decoder.framePosition());
                assertClip(reference, decoder, target, 1024);
            }
            decoder.seek(Duration.ofSeconds(Long.MAX_VALUE));
            assertEquals(audio.frames(), decoder.framePosition());
            assertEquals(-1, decoder.read(new byte[64], 0, 64));
            decoder.seek(Duration.ofSeconds(-1));
            assertEquals(0, decoder.framePosition());
            assertClip(reference, decoder, 0, 1024);
        }
        OggFileIndex index = OggFileProbe.scan(file);
        OggFileIndex.Segment segment = index.segments().getFirst();
        assertTrue(segment.seekPoints().size() < 30, "index must not retain every small page");
        OggFileIndex.SeekPoint late = segment.atOrBefore(19L * audio.sampleRate());
        assertTrue(late.byteOffset() > audio.bytes().length / 2L, "late seeks must use an interior byte offset");
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "vorbis-mono.ogg"})
    void indexedSeekUsesCachedHeadersWithoutRevisitingTheFileStart(String name) throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource(name), 10, 5);
        Path file = Files.write(tempDir.resolve(name), audio.bytes());
        byte[] reference;
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            reference = readAll(decoder);
        }
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            // The index has already validated and cached the headers. Poisoning the prefix makes
            // a replay-from-zero implementation fail, without relying on machine-dependent timing.
            try (SeekableByteChannel channel = Files.newByteChannel(file, StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[64]));
            }
            decoder.seek(Duration.ofSeconds(20));
            assertClip(reference, decoder, audio.sampleRate() * 20L, 1024);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "opus-stereo.ogg", "vorbis-mono.ogg", "vorbis-stereo.ogg"})
    void localIndexedSeeksCrossChainedStreamsIncludingReusedSerials(String name) throws Exception {
        byte[] first = resource(name);
        for (boolean reuseSerial : new boolean[] {false, true}) {
            byte[] second = reuseSerial ? first : OggTestPages.withSerial(first, 0x0123_4567);
            Path file = Files.write(tempDir.resolve("chain-" + name), OggTestPages.concat(first, second));
            try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
                byte[] reference = readAll(decoder);
                int rate = decoder.audioInfo().sampleRate();
                decoder.seek(Duration.ofMillis(4100));
                assertClip(reference, decoder, rate * 41L / 10, 1024);
                decoder.seek(Duration.ofMillis(1700));
                long target = rate * 17L / 10;
                assertEquals(target, decoder.framePosition());
                assertArrayEquals(Arrays.copyOfRange(reference,
                        Math.toIntExact(target * decoder.audioInfo().frameSize()), reference.length), readAll(decoder));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "vorbis-mono.ogg"})
    void remoteTailProbeDoesNotMistakeAChainedLastLinkForTotalDuration(String name) throws Exception {
        byte[] first = resource(name);
        for (boolean reuseSerial : new boolean[] {false, true}) {
            byte[] second = reuseSerial ? first : OggTestPages.withSerial(first, 0x1020_3040);
            byte[] bytes = OggTestPages.concat(first, second);
            RangeSource source = new RangeSource(bytes);
            try (AudioDecoder decoder = remote(source)) {
                assertEquals(Duration.ZERO, decoder.audioInfo().duration());
                assertEquals(0L, decoder.audioInfo().frameCount());
                byte[] reference = readAll(decoder);
                decoder.seek(Duration.ofMillis(3100));
                long target = decoder.audioInfo().sampleRate() * 31L / 10;
                assertClip(reference, decoder, target, 1024);
                decoder.seek(Duration.ZERO);
                assertArrayEquals(reference, readAll(decoder));
            }
            assertEquals(0, source.activeInputs);
        }
    }

    @Test
    void remoteDurationUsesOneBoundedTailReadAndSeeksReuseHeadersAndNearbyAnchors() throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource("opus-stereo.ogg"), 100, 5);
        assertTrue(audio.bytes().length > 1024 * 1024);
        RangeSource source = new RangeSource(audio.bytes());
        try (AudioDecoder decoder = remote(source)) {
            assertEquals(audio.frames(), decoder.audioInfo().frameCount());
            assertEquals(durationOf(audio.frames(), audio.sampleRate()), decoder.audioInfo().duration());
            assertEquals(List.of(0L, audio.bytes().length - 256L * 1024), source.offsets);
            assertTrue(source.bytesRead < 300 * 1024, "open must not scan/download all the audio");
            assertEquals(1, source.activeInputs, "the metadata input must be closed immediately");

            source.offsets.clear();
            decoder.seek(Duration.ofSeconds(180));
            assertEquals(180L * audio.sampleRate(), decoder.framePosition());
            assertTrue(source.offsets.stream().allMatch(offset -> offset > 0),
                    "a seek must not fetch byte zero to rebuild headers");
            long anchor = source.offsets.getLast();
            source.offsets.clear();
            decoder.seek(Duration.ofMillis(180_500));
            assertEquals(180_500L * audio.sampleRate() / 1000, decoder.framePosition());
            assertEquals(List.of(anchor), source.offsets, "nearby seeks should reuse their cached page anchor");

            source.offsets.clear();
            decoder.seek(Duration.ofSeconds(Long.MAX_VALUE));
            assertEquals(audio.frames(), decoder.framePosition());
            assertEquals(-1, decoder.read(new byte[64], 0, 64));
            assertTrue(source.offsets.isEmpty(), "seeking past a known end needs no network I/O");
            decoder.seek(Duration.ZERO);
            assertEquals(0, decoder.framePosition());
            assertTrue(decoder.read(new byte[64], 0, 64) > 0);
        }
        assertEquals(0, source.activeInputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "opus-stereo.ogg", "vorbis-mono.ogg", "vorbis-stereo.ogg"})
    void remoteSeeksAreFrameAccurateWithSmallAudioPages(String name) throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource(name), 12, 3);
        byte[] reference;
        Path file = Files.write(tempDir.resolve(name), audio.bytes());
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            reference = readAll(decoder);
        }
        RangeSource source = new RangeSource(audio.bytes());
        try (AudioDecoder decoder = remote(source)) {
            assertEquals(audio.frames(), decoder.audioInfo().frameCount());
            for (long millis : new long[] {20_017, 4_333, 27_111, 0, 500}) {
                decoder.seek(Duration.ofMillis(millis));
                long target = Math.round(millis * audio.sampleRate() / 1000.0);
                assertEquals(target, decoder.framePosition());
                assertClip(reference, decoder, target, 1024);
            }
        }
        assertEquals(0, source.activeInputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "vorbis-mono.ogg"})
    void aShortStreamWithOnlyAnEosAudioPageCanSeek(String name) throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource(name), 1, 128);
        Path file = Files.write(tempDir.resolve(name), audio.bytes());
        byte[] reference;
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            reference = readAll(decoder);
        }
        RangeSource source = new RangeSource(audio.bytes());
        try (AudioDecoder decoder = remote(source)) {
            source.offsets.clear();
            decoder.seek(Duration.ofSeconds(1));
            assertTrue(source.offsets.isEmpty(), "a forward seek inside pending PCM should use no I/O");
            assertClip(reference, decoder, audio.sampleRate(), 1024);
            decoder.seek(Duration.ofMillis(500));
            assertClip(reference, decoder, audio.sampleRate() / 2L, 1024);
            assertTrue(source.offsets.stream().anyMatch(offset -> offset > 0));
        }
        assertEquals(0, source.activeInputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "vorbis-mono.ogg"})
    void alreadyDecodedTerminalEosCanSupplyDurationWithoutRangeSupport(String name) throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource(name), 1, 128);
        try (AudioDecoder decoder = OggAudioDecoder.openRemote(new ByteArrayInputStream(audio.bytes()),
                "short non-range stream", audio.bytes().length, null)) {
            assertEquals(audio.frames(), decoder.audioInfo().frameCount());
            assertEquals(durationOf(audio.frames(), audio.sampleRate()), decoder.audioInfo().duration());
            assertFalse(decoder.isSeekable());
            assertEquals(audio.frames() * audio.channels() * 2L, readAll(decoder).length);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "vorbis-mono.ogg"})
    void missingEosLeavesDurationUnknownButDoesNotBreakPlaybackOrSeeking(String name) throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource(name), 5, 5);
        byte[] incomplete = OggTestPages.dropLastPage(audio.bytes());
        RangeSource source = new RangeSource(incomplete);
        try (AudioDecoder decoder = remote(source)) {
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertTrue(decoder.isSeekable());
            byte[] reference = readAll(decoder);
            decoder.seek(Duration.ofSeconds(7));
            assertClip(reference, decoder, decoder.audioInfo().sampleRate() * 7L, 1024);
        }
        assertEquals(0, source.activeInputs);
    }

    @Test
    void failedOptionalTailProbeStillAllowsPlayback() throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource("opus-mono.ogg"), 30, 5);
        RangeSource source = new RangeSource(audio.bytes());
        source.failNonzero = true;
        try (AudioDecoder decoder = remote(source)) {
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertTrue(decoder.read(new byte[8192], 0, 8192) > 0);
            decoder.seek(Duration.ofSeconds(1)); // safe replay fallback when tail metadata is unavailable
            assertEquals(48_000, decoder.framePosition());
        }
        assertEquals(0, source.activeInputs);
    }

    @Test
    void failedRangeOpenLeavesTheExistingDecoderAndPositionUsable() throws Exception {
        OggTestPages.RepeatedAudio audio = OggTestPages.repeatAudio(resource("opus-mono.ogg"), 30, 5);
        Path file = Files.write(tempDir.resolve("reference.ogg"), audio.bytes());
        byte[] reference;
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            reference = readAll(decoder);
        }
        RangeSource source = new RangeSource(audio.bytes());
        try (AudioDecoder decoder = remote(source)) {
            decoder.seek(Duration.ofSeconds(25));
            long before = decoder.framePosition();
            source.failNonzero = true;
            assertThrows(UncheckedIOException.class, () -> decoder.seek(Duration.ofSeconds(26)));
            assertEquals(before, decoder.framePosition());
            assertClip(reference, decoder, before, 1024);
        }
        assertEquals(0, source.activeInputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "opus-stereo.ogg", "vorbis-mono.ogg", "vorbis-stereo.ogg"})
    void remoteDurationAndSeekAccountForNonzeroInitialGranules(String name) throws Exception {
        byte[] bytes = name.startsWith("opus") ? OggTestPages.shiftPositiveGranules(resource(name), 4096)
                : OggTestPages.withValidVorbisInitialOrigin(resource(name), 4096).bytes();
        Path file = Files.write(tempDir.resolve(name), bytes);
        byte[] reference;
        long frames;
        Duration duration;
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            reference = readAll(decoder);
            frames = decoder.audioInfo().frameCount();
            duration = decoder.audioInfo().duration();
        }
        RangeSource source = new RangeSource(bytes);
        try (AudioDecoder decoder = remote(source)) {
            assertEquals(frames, decoder.audioInfo().frameCount());
            assertEquals(duration, decoder.audioInfo().duration());
            decoder.seek(Duration.ofMillis(2100));
            assertClip(reference, decoder, decoder.audioInfo().sampleRate() * 21L / 10, 1024);
        }
        assertEquals(0, source.activeInputs);
    }

    @ParameterizedTest
    @ValueSource(strings = {"opus-mono.ogg", "opus-stereo.ogg"})
    void rangeSeekTrimsPreskipThatExtendsAcrossAudioPages(String name) throws Exception {
        byte[] bytes = OggTestPages.withOpusPreSkip(resource(name), 60_000);
        Path file = Files.write(tempDir.resolve(name), bytes);
        byte[] reference;
        long frames;
        try (AudioDecoder decoder = OggAudioDecoder.openLocal(file)) {
            reference = readAll(decoder);
            frames = decoder.audioInfo().frameCount();
            decoder.seek(Duration.ofMillis(900));
            assertClip(reference, decoder, 43_200, 1024);
        }
        RangeSource source = new RangeSource(bytes);
        try (AudioDecoder decoder = remote(source)) {
            assertEquals(frames, decoder.audioInfo().frameCount());
            decoder.seek(Duration.ofMillis(900));
            assertClip(reference, decoder, 43_200, 1024);
        }
        assertEquals(0, source.activeInputs);
    }

    private static AudioDecoder remote(RangeSource source) throws IOException {
        return OggAudioDecoder.openRemote(source.apply(0L), "range fixture", source.bytes.length, source);
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = OggSeekingTest.class.getResourceAsStream("/audio/ogg/" + name)) {
            if (in == null) {
                throw new IOException("missing fixture: " + name);
            }
            return in.readAllBytes();
        }
    }

    private static void assertClip(byte[] reference, AudioDecoder decoder, long frame, int frames) throws IOException {
        assertEquals(frame, decoder.framePosition());
        int frameSize = decoder.audioInfo().frameSize();
        int start = Math.toIntExact(frame * frameSize);
        byte[] actual = new byte[Math.min(frames * frameSize, reference.length - start)];
        int count = decoder.read(actual, 0, actual.length);
        assertEquals(actual.length, count);
        byte[] expected = Arrays.copyOfRange(reference, start, start + actual.length);
        if (decoder.audioInfo().decoderName().equals("Concentus")) {
            // Opus preroll warms up prediction but need not restore every bit of its history.
            // Check sample alignment and tiny PCM16 residuals instead of demanding bit identity.
            int maxDifference = 0;
            double sumSquares = 0;
            for (int i = 0; i < actual.length; i += 2) {
                int a = (short) ((actual[i] & 0xFF) | (actual[i + 1] << 8));
                int b = (short) ((expected[i] & 0xFF) | (expected[i + 1] << 8));
                int difference = Math.abs(a - b);
                maxDifference = Math.max(maxDifference, difference);
                sumSquares += (double) difference * difference;
            }
            double rmsDifference = Math.sqrt(sumSquares / (actual.length / 2));
            assertTrue(maxDifference <= 32 && rmsDifference <= 8,
                    "Opus seek at frame " + frame + ": max PCM difference " + maxDifference
                            + ", RMS " + rmsDifference);
        } else {
            assertArrayEquals(expected, actual);
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

    private static Duration durationOf(long frames, int rate) {
        return Duration.ofSeconds(frames / rate, Math.round((frames % rate) * 1_000_000_000.0 / rate));
    }

    private static final class RangeSource implements LongFunction<InputStream> {
        private final byte[] bytes;
        private final List<Long> offsets = new ArrayList<>();
        private long bytesRead;
        private int activeInputs;
        private boolean failNonzero;

        RangeSource(byte[] bytes) {
            this.bytes = bytes;
        }

        @Override
        public InputStream apply(long offset) {
            offsets.add(offset);
            if (failNonzero && offset > 0) {
                throw new UncheckedIOException(new IOException("range unavailable"));
            }
            activeInputs++;
            return new InputStream() {
                private final ByteArrayInputStream input = new ByteArrayInputStream(bytes,
                        Math.toIntExact(offset), bytes.length - Math.toIntExact(offset));
                private boolean closed;

                @Override
                public int read() {
                    int value = input.read();
                    if (value >= 0) {
                        bytesRead++;
                    }
                    return value;
                }

                @Override
                public int read(byte[] buffer, int start, int length) {
                    int count = input.read(buffer, start, length);
                    if (count > 0) {
                        bytesRead += count;
                    }
                    return count;
                }

                @Override
                public void close() {
                    if (!closed) {
                        closed = true;
                        activeInputs--;
                    }
                }
            };
        }
    }
}
