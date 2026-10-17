package io.github.sinaraz.internal.ogg;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

import io.github.sinaraz.model.CoverArt;

/** Best-effort extraction of the FLAC-picture and COVERART Vorbis-comment conventions. */
final class OggPictureParser {

    private static final int MAX_COMMENT_COUNT = 8192;
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_BASE64_BYTES = (MAX_IMAGE_BYTES * 4 + 2) / 3 + 8;

    private OggPictureParser() {}

    static Optional<CoverArt> fromTags(OggHeaderParser.Codec codec, byte[] packet) {
        int prefixLength = switch (codec) {
            case OPUS -> 8;
            case VORBIS -> 7;
            case UNKNOWN -> 0;
        };
        if (prefixLength == 0 || packet.length < prefixLength
                || (codec == OggHeaderParser.Codec.OPUS && !matches(packet, "OpusTags".getBytes(StandardCharsets.US_ASCII)))
                || (codec == OggHeaderParser.Codec.VORBIS
                    && !matches(packet, new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'}))) {
            return Optional.empty();
        }

        try {
            Cursor cursor = new Cursor(packet, prefixLength, ByteOrder.LITTLE_ENDIAN);
            long vendorLength = cursor.readUnsigned32();
            cursor.skip(vendorLength);
            long count = cursor.readUnsigned32();
            if (count > MAX_COMMENT_COUNT) {
                return Optional.empty();
            }
            CoverArt firstPicture = null;
            byte[] coverArtBytes = null;
            String coverMime = "";
            for (int i = 0; i < count; i++) {
                long length = cursor.readUnsigned32();
                if (length > packet.length || length > Integer.MAX_VALUE) {
                    return Optional.empty();
                }
                int offset = cursor.position();
                cursor.skip(length);
                int end = offset + (int) length;
                int equals = findEquals(packet, offset, end);
                if (equals <= offset) {
                    continue;
                }
                String key = asciiKey(packet, offset, equals - offset);
                if (key == null) {
                    continue;
                }
                int valueOffset = equals + 1;
                int valueLength = end - valueOffset;
                if (valueLength <= 0 || valueLength > MAX_BASE64_BYTES) {
                    continue;
                }
                if (key.equals("METADATA_BLOCK_PICTURE")) {
                    ParsedPicture picture = parsePicture(decodeBase64(packet, valueOffset, valueLength));
                    if (picture != null) {
                        if (picture.type() == 3) {
                            return Optional.of(picture.cover());
                        }
                        if (firstPicture == null) {
                            firstPicture = picture.cover();
                        }
                    }
                } else if (key.equals("COVERART")) {
                    byte[] bytes = decodeBase64(packet, valueOffset, valueLength);
                    if (bytes != null && bytes.length > 0 && coverArtBytes == null) {
                        coverArtBytes = bytes;
                    }
                } else if (key.equals("COVERARTMIME")) {
                    coverMime = new String(packet, valueOffset, valueLength, StandardCharsets.UTF_8).strip();
                }
            }
            if (firstPicture != null) {
                return Optional.of(firstPicture);
            }
            return coverArtBytes == null
                    ? Optional.empty()
                    : Optional.of(new CoverArt(coverMime.isEmpty() ? guessMime(coverArtBytes) : coverMime,
                            coverArtBytes));
        } catch (IllegalArgumentException | IndexOutOfBoundsException e) {
            // A broken optional picture field should not make otherwise playable audio fail.
            return Optional.empty();
        }
    }

    private static byte[] decodeBase64(byte[] packet, int offset, int length) {
        try {
            byte[] bytes = Base64.getDecoder().decode(java.util.Arrays.copyOfRange(packet, offset, offset + length));
            return bytes.length <= MAX_IMAGE_BYTES ? bytes : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static ParsedPicture parsePicture(byte[] block) {
        if (block == null) {
            return null;
        }
        Cursor cursor = new Cursor(block, 0, ByteOrder.BIG_ENDIAN);
        long type = cursor.readUnsigned32();
        long mimeLength = cursor.readUnsigned32();
        if (mimeLength > block.length) {
            return null;
        }
        byte[] mimeBytes = cursor.readBytes((int) mimeLength);
        String mime = new String(mimeBytes, StandardCharsets.US_ASCII).strip();
        long descriptionLength = cursor.readUnsigned32();
        if (descriptionLength > block.length) {
            return null;
        }
        cursor.skip(descriptionLength);
        cursor.skip(16); // width, height, color depth, and indexed-color count
        long imageLength = cursor.readUnsigned32();
        if (imageLength <= 0 || imageLength > MAX_IMAGE_BYTES || imageLength > cursor.remaining()) {
            return null;
        }
        byte[] image = cursor.readBytes((int) imageLength);
        return new ParsedPicture(type, new CoverArt(mime.isEmpty() ? guessMime(image) : mime, image));
    }

    private static int findEquals(byte[] packet, int from, int end) {
        for (int i = from; i < end; i++) {
            if (packet[i] == '=') {
                return i;
            }
        }
        return -1;
    }

    private static String asciiKey(byte[] packet, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if ((packet[i] & 0x80) != 0) {
                return null;
            }
        }
        return new String(packet, offset, length, StandardCharsets.US_ASCII).toUpperCase(Locale.ROOT);
    }

    private static boolean matches(byte[] packet, byte[] prefix) {
        if (packet.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (packet[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String guessMime(byte[] bytes) {
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }
        if (bytes.length > 4 && bytes[0] == (byte) 0x89 && bytes[1] == 'P'
                && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private record ParsedPicture(long type, CoverArt cover) {}

    private static final class Cursor {
        private final byte[] data;
        private final ByteOrder byteOrder;
        private int position;

        private Cursor(byte[] data, int position, ByteOrder byteOrder) {
            this.data = data;
            this.position = position;
            this.byteOrder = byteOrder;
        }

        private long readUnsigned32() {
            require(4);
            long value = ByteBuffer.wrap(data, position, 4).order(byteOrder).getInt()
                    & 0xFFFF_FFFFL;
            position += 4;
            return value;
        }

        private void skip(long length) {
            if (length < 0 || length > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("invalid Ogg comment length");
            }
            require((int) length);
            position += (int) length;
        }

        private byte[] readBytes(int length) {
            require(length);
            byte[] result = java.util.Arrays.copyOfRange(data, position, position + length);
            position += length;
            return result;
        }

        private int position() {
            return position;
        }

        private long remaining() {
            return data.length - (long) position;
        }

        private void require(int length) {
            if (length < 0 || length > data.length - position) {
                throw new IndexOutOfBoundsException("truncated Ogg picture metadata");
            }
        }
    }
}
