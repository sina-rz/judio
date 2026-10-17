package io.github.sinaraz.internal.ogg;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import io.github.sinaraz.error.CorruptFileException;
import io.github.sinaraz.model.TrackMetadata;

/** Defensive parser for the shared Vorbis-comment layout used by Vorbis and OpusTags. */
final class OggCommentParser {

    private static final int MAX_COMMENT_COUNT = 8192;
    private static final int MAX_TEXT_BYTES = OggPacketReader.MAX_PACKET_BYTES;

    private OggCommentParser() {}

    static TrackMetadata parseOpusTags(byte[] packet) {
        requirePrefix(packet, "OpusTags".getBytes(StandardCharsets.US_ASCII), "OpusTags");
        Cursor cursor = new Cursor(packet, 8, packet.length);
        return parseComments(cursor, false);
    }

    static TrackMetadata parseVorbisComment(byte[] packet) {
        byte[] prefix = new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'};
        requirePrefix(packet, prefix, "Vorbis comment");
        Cursor cursor = new Cursor(packet, prefix.length, packet.length);
        return parseComments(cursor, true);
    }

    private static TrackMetadata parseComments(Cursor cursor, boolean framingBit) {
        cursor.require(4, "missing vendor length");
        long vendorLength = cursor.readUnsigned32();
        if (vendorLength > MAX_TEXT_BYTES) {
            throw corrupt("comment vendor string exceeds the safety limit");
        }
        cursor.skip((int) vendorLength, "truncated comment vendor string");
        cursor.require(4, "missing comment count");
        long count = cursor.readUnsigned32();
        if (count > MAX_COMMENT_COUNT) {
            throw corrupt("comment count exceeds the safety limit");
        }

        Map<String, String> values = new HashMap<>();
        boolean hasCover = false;
        for (int i = 0; i < (int) count; i++) {
            cursor.require(4, "truncated comment length");
            long length = cursor.readUnsigned32();
            if (length > MAX_TEXT_BYTES) {
                throw corrupt("comment string exceeds the safety limit");
            }
            int commentOffset = cursor.position();
            cursor.skip((int) length, "truncated comment string");
            int equals = findEquals(cursor.data(), commentOffset, (int) length);
            if (equals <= 0) {
                continue;
            }
            String key = decodeKey(cursor.data(), commentOffset, equals - commentOffset);
            if (key == null) {
                continue;
            }
            if (key.equals("METADATA_BLOCK_PICTURE") || key.equals("COVERART")) {
                hasCover |= equals + 1 < commentOffset + length;
                continue;
            }
            String value = decodeUtf8(cursor.data(), equals + 1, commentOffset + (int) length - equals - 1)
                    .strip();
            if (value.isEmpty()) {
                continue;
            }
            switch (key) {
                case "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "ALBUM ARTIST", "GENRE",
                        "DATE", "YEAR", "TRACKNUMBER", "DISCNUMBER" -> values.putIfAbsent(key, value);
                default -> {
                    // Unknown comments are legal and intentionally ignored.
                }
            }
        }
        if (framingBit) {
            cursor.require(1, "missing Vorbis comment framing bit");
            if ((cursor.readByte() & 0x01) == 0) {
                throw corrupt("invalid Vorbis comment framing bit");
            }
        }

        return new TrackMetadata(
                first(values, "TITLE"),
                first(values, "ARTIST"),
                first(values, "ALBUM"),
                first(values, "ALBUMARTIST", "ALBUM ARTIST"),
                first(values, "GENRE"),
                parseYear(first(values, "DATE", "YEAR")),
                parseNumber(first(values, "TRACKNUMBER")),
                parseNumber(first(values, "DISCNUMBER")),
                hasCover);
    }

    private static int findEquals(byte[] bytes, int offset, int length) {
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            if (bytes[i] == '=') {
                return i;
            }
        }
        return -1;
    }

    private static String decodeKey(byte[] bytes, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if ((bytes[i] & 0x80) != 0) {
                return null;
            }
        }
        return new String(bytes, offset, length, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }

    private static String decodeUtf8(byte[] bytes, int offset, int length) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length)).toString();
        } catch (CharacterCodingException e) {
            throw new CorruptFileException("open", "invalid UTF-8 in Ogg comments", null, e);
        }
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static Integer parseYear(String value) {
        if (value == null) {
            return null;
        }
        int end = Math.min(4, value.length());
        if (end < 4) {
            return null;
        }
        try {
            return Integer.valueOf(value.substring(0, 4));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseNumber(String value) {
        if (value == null) {
            return null;
        }
        int slash = value.indexOf('/');
        String prefix = (slash >= 0 ? value.substring(0, slash) : value).strip();
        try {
            return Integer.valueOf(prefix);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void requirePrefix(byte[] packet, byte[] prefix, String description) {
        if (packet.length < prefix.length) {
            throw corrupt("truncated " + description + " packet");
        }
        for (int i = 0; i < prefix.length; i++) {
            if (packet[i] != prefix[i]) {
                throw corrupt("invalid " + description + " signature");
            }
        }
    }

    private static CorruptFileException corrupt(String message) {
        return new CorruptFileException("open", message, null, null);
    }

    private static final class Cursor {
        private final byte[] data;
        private final int end;
        private int position;

        private Cursor(byte[] data, int position, int end) {
            this.data = data;
            this.position = position;
            this.end = end;
        }

        private void require(int count, String message) {
            if (count < 0 || count > end - position) {
                throw corrupt(message);
            }
        }

        private long readUnsigned32() {
            require(4, "truncated Ogg comment field");
            long value = ByteBuffer.wrap(data, position, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFF_FFFFL;
            position += 4;
            return value;
        }

        private int readByte() {
            require(1, "truncated Ogg comment field");
            return data[position++] & 0xFF;
        }

        private int position() {
            return position;
        }

        private byte[] data() {
            return data;
        }

        private void skip(int length, String message) {
            require(length, message);
            position += length;
        }

    }
}
