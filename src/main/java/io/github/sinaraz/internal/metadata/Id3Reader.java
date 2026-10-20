package io.github.sinaraz.internal.metadata;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.TrackMetadata;

/**
 * Pure-Java reader for ID3v2.3/v2.4 text frames and APIC cover art, with ID3v1 as a fallback.
 *
 * <p>Parsing is defensive: every size is bounds-checked, oversized tags are ignored, and a
 * malformed frame ends the frame scan without throwing. The result is "whatever could be read".
 * ID3v2.2 tags are not parsed (they are rare and their frame IDs differ); the ID3v1 fallback
 * still applies to them.
 */
public final class Id3Reader {

    /**
     * Upper bound on the ID3v2 body we are willing to read. Real tags are a few hundred KiB.
     */
    static final int MAX_TAG_BYTES = 16 * 1024 * 1024;

    /**
     * Parsed tag content. Fields may be null.
     */
    public record Result(TrackMetadata metadata, CoverArt coverArt) {}

    /**
     * Reads metadata and cover art. Never throws for malformed tags; throws only for I/O errors.
     */
    public static Result read(Path file) throws IOException {
        try (SeekableByteChannel ch = Files.newByteChannel(file)) {
            long size = ch.size();
            Map<String, String> v2 = new HashMap<>();
            CoverArt cover = null;

            ByteBuffer head = ByteBuffer.allocate(10);
            if (readAt(ch, 0, head) == 10 && head.get(0) == 'I' && head.get(1) == 'D' && head.get(2) == '3') {
                int major = head.get(3) & 0xFF;
                int flags = head.get(5) & 0xFF;
                int tagSize = syncSafe(head.array(), 6);
                if ((major == 3 || major == 4) && tagSize > 0 && tagSize <= MAX_TAG_BYTES) {
                    byte[] body = new byte[tagSize];
                    int got = readAt(ch, 10, ByteBuffer.wrap(body));
                    if (got > 0) {
                        if (major == 3 && (flags & 0x80) != 0) {
                            // v2.3 unsynchronisation: 0xFF 0x00 in the stored tag
                            // is an escaped 0xFF. Decode before frame parsing.
                            // (v2.4 signals unsynchronisation per frame instead.)
                            body = removeUnsynchronisation(body, got);
                            got = body.length;
                        }
                        cover = parseFrames(body, got, major, flags, v2);
                    }
                }
            }

            Map<String, String> v1 = new HashMap<>();
            if (size >= 128) {
                ByteBuffer tail = ByteBuffer.allocate(128);
                if (readAt(ch, size - 128, tail) == 128 && tail.get(0) == 'T' && tail.get(1) == 'A'
                        && tail.get(2) == 'G') {
                    v1.put("title", latin1(tail.array(), 3, 30));
                    v1.put("artist", latin1(tail.array(), 33, 30));
                    v1.put("album", latin1(tail.array(), 63, 30));
                    v1.put("year", latin1(tail.array(), 93, 4));
                    // ID3v1.1: a zero byte at 125 followed by a non-zero track number in byte 126.
                    if (tail.get(125) == 0 && tail.get(126) != 0) {
                        v1.put("track", Integer.toString(tail.get(126) & 0xFF));
                    }
                }
            }

            TrackMetadata metadata = new TrackMetadata(
                    firstNonBlank(v2.get("TIT2"), v1.get("title")),
                    firstNonBlank(v2.get("TPE1"), v1.get("artist")),
                    firstNonBlank(v2.get("TALB"), v1.get("album")),
                    v2.get("TPE2"),
                    v2.get("TCON"),
                    parseYear(firstNonBlank(v2.get("TDRC"), v2.get("TYER"), v1.get("year"))),
                    parseLeadingInt(firstNonBlank(v2.get("TRCK"), v1.get("track"))),
                    parseLeadingInt(v2.get("TPOS")),
                    cover != null);
            return new Result(metadata, cover);
        }
    }

    /**
     * Extracts embedded cover art only.
     */
    public static Optional<CoverArt> readCoverArt(Path file) throws IOException {
        return Optional.ofNullable(read(file).coverArt());
    }

    // ----------------------------------------------------------------------

    private static CoverArt parseFrames(byte[] body, int length, int major, int flags, Map<String, String> out) {
        int pos = 0;
        if ((flags & 0x40) != 0 && length >= 4) { // extended header present
            pos = major == 4 ? syncSafe(body, 0) : 4 + be32(body, 0);
        }
        CoverArt cover = null;
        while (pos + 10 <= length) {
            if (body[pos] == 0) {
                break; // padding
            }
            String id = new String(body, pos, 4, StandardCharsets.ISO_8859_1);
            int frameSize = major == 4 ? syncSafe(body, pos + 4) : be32(body, pos + 4);
            if (frameSize <= 0 || pos + 10 + frameSize > length) {
                break; // corrupt or truncated frame: stop scanning but keep what we have
            }
            int start = pos + 10;
            if ("APIC".equals(id) && cover == null) {
                cover = parseApic(body, start, frameSize);
            } else if (id.charAt(0) == 'T' && !"TXXX".equals(id)) {
                String text = decodeText(body, start, frameSize);
                if (text != null) {
                    out.putIfAbsent(id, text);
                }
            }
            pos = start + frameSize;
        }
        return cover;
    }

    private static String decodeText(byte[] b, int start, int len) {
        if (len < 1) {
            return null;
        }
        int enc = b[start] & 0xFF;
        int textLen = len - 1;
        String text = switch (enc) {
            case 0 -> new String(b, start + 1, textLen, StandardCharsets.ISO_8859_1);
            case 1 -> new String(b, start + 1, textLen, StandardCharsets.UTF_16); // honours BOM
            case 2 -> new String(b, start + 1, textLen, StandardCharsets.UTF_16BE);
            case 3 -> new String(b, start + 1, textLen, StandardCharsets.UTF_8);
            default -> null;
        };
        if (text == null) {
            return null;
        }
        int nul = text.indexOf('\u0000');
        if (nul >= 0) {
            text = text.substring(0, nul); // multi-value frames: keep the first value
        }
        text = text.strip();
        return text.isEmpty() ? null : text;
    }

    private static CoverArt parseApic(byte[] b, int start, int len) {
        int end = start + len;
        int enc = b[start] & 0xFF;
        int p = start + 1;
        int mimeEnd = indexOfZero(b, p, end);
        if (mimeEnd < 0 || mimeEnd + 1 >= end) {
            return null;
        }
        String mime = new String(b, p, mimeEnd - p, StandardCharsets.ISO_8859_1).strip();
        if ("-->".equals(mime)) {
            return null; // image is a URL, not embedded bytes
        }
        p = mimeEnd + 2; // skip terminator and picture-type byte
        boolean wide = enc == 1 || enc == 2;
        int dataStart = wide ? indexOfDoubleZero(b, p, end) : indexOfZero(b, p, end);
        if (dataStart < 0) {
            return null;
        }
        dataStart += wide ? 2 : 1;
        if (dataStart >= end) {
            return null;
        }
        byte[] data = new byte[end - dataStart];
        System.arraycopy(b, dataStart, data, 0, data.length);
        return new CoverArt(mime.isEmpty() ? guessMime(data) : mime, data);
    }

    private static String guessMime(byte[] data) {
        if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (data.length > 4 && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        return "application/octet-stream";
    }

    /**
     * Removes v2.3 unsynchronisation escapes ({@code FF 00} -> {@code FF}).
     */
    private static byte[] removeUnsynchronisation(byte[] body, int length) {
        byte[] out = new byte[length];
        int o = 0;
        for (int i = 0; i < length; i++) {
            out[o++] = body[i];
            if (body[i] == (byte) 0xFF && i + 2 < length && body[i + 1] == 0
                    && (body[i + 2] == 0 || (body[i + 2] & 0xFF) >= 0xE0)) i++;
        }
        return java.util.Arrays.copyOf(out, o);
    }

    private static int indexOfZero(byte[] b, int from, int end) {
        for (int i = from; i < end; i++) {
            if (b[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static int indexOfDoubleZero(byte[] b, int from, int end) {
        for (int i = from; i + 1 < end; i += 2) {
            if (b[i] == 0 && b[i + 1] == 0) {
                return i;
            }
        }
        return -1;
    }

    private static String latin1(byte[] b, int off, int len) {
        int end = off + len;
        int stop = off;
        while (stop < end && b[stop] != 0) {
            stop++;
        }
        String s = new String(b, off, stop - off, StandardCharsets.ISO_8859_1).strip();
        return s.isEmpty() ? null : s;
    }

    private static int syncSafe(byte[] b, int off) {
        return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
                | ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
    }

    private static int be32(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readAt(SeekableByteChannel ch, long position, ByteBuffer buf) throws IOException {
        ch.position(position);
        int total = 0;
        while (buf.hasRemaining()) {
            int n = ch.read(buf);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static Integer parseYear(String text) {
        if (text == null || text.length() < 4) {
            return null;
        }
        return parseInt(text.substring(0, 4));
    }

    private static Integer parseLeadingInt(String text) {
        if (text == null) {
            return null;
        }
        int slash = text.indexOf('/');
        return parseInt(slash >= 0 ? text.substring(0, slash) : text);
    }

    private static Integer parseInt(String text) {
        try {
            int value = Integer.parseInt(text.strip());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
