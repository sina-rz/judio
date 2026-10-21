package io.github.sinaraz.internal.decoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects a file's container by its leading bytes. File extensions are not trusted: a file named
 * {@code song.wav} that contains MP3 data is still decoded as MP3.
 */
public final class FormatSniffer {

    /** Container families recognised by the sniffer. */
    public enum Format {
        /** MPEG-1/2/2.5 audio (Layers I, II, and III are decoded). */
        MP3,
        /** RIFF/WAVE. */
        WAVE,
        /** AIFF or AIFF-C. */
        AIFF,
        /** Sun/NeXT AU. */
        AU,
        /** FLAC (detected but not decoded). */
        FLAC,
        /** Ogg container; the codec is identified from its logical-stream headers. */
        OGG,
        /** Not recognised. */
        UNKNOWN
    }

    private static final int HEADER_BYTES = 16;


    /**
     * Detects the container from the first bytes of {@code file}.
     *
     * @param file file to inspect
     * @return the detected container, or {@link Format#UNKNOWN}
     * @throws IOException if the file cannot be read
     */
    public static Format detect(Path file) throws IOException {
        byte[] head = new byte[HEADER_BYTES];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(head, 0, HEADER_BYTES);
        }
        return detect(head, n);
    }

    /** Detects the format from the first {@code n} bytes of a file. */
    public static Format detect(byte[] head, int n) {
        if (startsWith(head, n, "RIFF") && n >= 12 && matches(head, 8, "WAVE")) {
            return Format.WAVE;
        }
        if (startsWith(head, n, "FORM") && n >= 12 && (matches(head, 8, "AIFF") || matches(head, 8, "AIFC"))) {
            return Format.AIFF;
        }
        if (startsWith(head, n, ".snd")) {
            return Format.AU;
        }
        if (startsWith(head, n, "fLaC")) {
            return Format.FLAC;
        }
        if (startsWith(head, n, "OggS")) {
            return Format.OGG;
        }
        if (startsWith(head, n, "ID3")) {
            return Format.MP3;
        }
        // MPEG audio frame sync: 11 set bits, then version and layer bits.
        // Layers I, II and III are all decoded (by JLayer), so all are MP3 here;
        // reserved version/layer and bad bitrate/rate indices are rejected so
        // random 0xFF 0xFB-prefixed junk is not misclassified.
        if (n >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
            int version = (head[1] >> 3) & 0x03;  // 01 is reserved
            int layer = (head[1] >> 1) & 0x03;    // 00 is reserved
            int bitrateIndex = (head[2] >> 4) & 0x0F;
            int rateIndex = (head[2] >> 2) & 0x03;
            if (version != 0x01 && layer != 0x00
                    && bitrateIndex != 0x00 && bitrateIndex != 0x0F && rateIndex != 0x03) {
                return Format.MP3;
            }
        }
        return Format.UNKNOWN;
    }

    private static boolean startsWith(byte[] head, int n, String magic) {
        return n >= magic.length() && matches(head, 0, magic);
    }

    private static boolean matches(byte[] head, int offset, String text) {
        for (int i = 0; i < text.length(); i++) {
            if (head[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
