package io.github.sinaraz.internal.decoder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.error.UnsupportedAudioFormatException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DecoderFactoryTest {

    @TempDir
    Path dir;

    @Test
    void sniffsContentNotExtension() {
        assertEquals(FormatSniffer.Format.WAVE,
                FormatSniffer.detect("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.ISO_8859_1), 16));
        assertEquals(FormatSniffer.Format.AIFF,
                FormatSniffer.detect("FORM\0\0\0\0AIFFCOMM".getBytes(StandardCharsets.ISO_8859_1), 16));
        assertEquals(FormatSniffer.Format.AU,
                FormatSniffer.detect(".snd\0\0\0\u0018".getBytes(StandardCharsets.ISO_8859_1), 8));
        assertEquals(FormatSniffer.Format.MP3,
                FormatSniffer.detect("ID3\u0004\0".getBytes(StandardCharsets.ISO_8859_1), 5));
        assertEquals(FormatSniffer.Format.MP3, FormatSniffer.detect(new byte[] {(byte) 0xFF, (byte) 0xFB}, 2));
        assertEquals(FormatSniffer.Format.FLAC, FormatSniffer.detect("fLaC".getBytes(StandardCharsets.ISO_8859_1), 4));
        assertEquals(FormatSniffer.Format.OGG, FormatSniffer.detect("OggS".getBytes(StandardCharsets.ISO_8859_1), 4));
        assertEquals(FormatSniffer.Format.UNKNOWN, FormatSniffer.detect(new byte[] {1, 2, 3, 4}, 4));
    }

    @Test
    void flacAndOggAreReportedAsUnsupported() throws IOException {
        Path flac = Files.write(dir.resolve("x.flac"), "fLaC\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        Path ogg = Files.write(dir.resolve("y.ogg"), "OggS\0\0\0\0".getBytes(StandardCharsets.ISO_8859_1));
        assertThrows(UnsupportedAudioFormatException.class, () -> DecoderFactory.standard().open(flac));
        assertThrows(UnsupportedAudioFormatException.class, () -> DecoderFactory.standard().open(ogg));
    }

    @Test
    void unknownContentIsRejectedEvenWithAudioExtension() throws IOException {
        Path junk = Files.write(dir.resolve("junk.wav"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12});
        assertThrows(UnsupportedAudioFormatException.class, () -> DecoderFactory.standard().open(junk));
    }

    @Test
    void missingFileIsAnIoError() {
        assertThrows(IOException.class, () -> DecoderFactory.standard().open(dir.resolve("absent.mp3")));
    }
}
