package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.model.CoverArt;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggPictureParserTest {

    private static final byte[] IMAGE = {
            (byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3, 4
    };

    @Test
    void decodesVorbisFlacPictureCommentAndPrefersFrontCover() throws Exception {
        byte[] packet = tags(new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'},
                "METADATA_BLOCK_PICTURE=" + Base64.getEncoder().encodeToString(picture(4, IMAGE)));
        CoverArt cover = OggPictureParser.fromTags(OggHeaderParser.Codec.VORBIS, packet).orElseThrow();
        assertEquals("image/png", cover.mimeType());
        assertArrayEquals(IMAGE, cover.data());
        assertTrue(OggCommentParser.parseVorbisComment(packet).hasCoverArt());
    }

    @Test
    void decodesOpusCoverartAndReadsMimeEvenWhenItFollowsTheImage() throws Exception {
        byte[] packet = tags("OpusTags".getBytes(StandardCharsets.US_ASCII),
                "COVERART=" + Base64.getEncoder().encodeToString(IMAGE),
                "COVERARTMIME=image/png");
        CoverArt cover = OggPictureParser.fromTags(OggHeaderParser.Codec.OPUS, packet).orElseThrow();
        assertEquals("image/png", cover.mimeType());
        assertArrayEquals(IMAGE, cover.data());
        assertTrue(OggCommentParser.parseOpusTags(packet).hasCoverArt());
    }

    @Test
    void malformedOptionalPictureDoesNotThrowOrInventCoverArt() throws Exception {
        byte[] packet = tags("OpusTags".getBytes(StandardCharsets.US_ASCII), "COVERART=not-base64!");
        assertTrue(OggPictureParser.fromTags(OggHeaderParser.Codec.OPUS, packet).isEmpty());
        assertTrue(OggCommentParser.parseOpusTags(packet).hasCoverArt());
    }

    private static byte[] tags(byte[] prefix, String... comments) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(prefix);
        writeLe32(out, 0); // empty vendor string
        writeLe32(out, comments.length);
        for (String comment : comments) {
            byte[] bytes = comment.getBytes(StandardCharsets.UTF_8);
            writeLe32(out, bytes.length);
            out.write(bytes);
        }
        if (prefix.length == 7 && prefix[0] == 3) {
            out.write(1); // Vorbis comment framing bit
        }
        return out.toByteArray();
    }

    private static byte[] picture(int type, byte[] image) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] mime = "image/png".getBytes(StandardCharsets.US_ASCII);
        writeBe32(out, type);
        writeBe32(out, mime.length);
        out.write(mime);
        writeBe32(out, 0); // description
        writeBe32(out, 1); // width
        writeBe32(out, 1); // height
        writeBe32(out, 8); // depth
        writeBe32(out, 0); // indexed colors
        writeBe32(out, image.length);
        out.write(image);
        return out.toByteArray();
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static void writeBe32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }
}
