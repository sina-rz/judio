package io.github.sinaraz.internal.metadata;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.TrackMetadata;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Id3ReaderTest {

    @TempDir
    Path dir;

    private static final byte[] AUDIO = {(byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64, 0, 0, 0, 0};

    private static byte[] frame(String id, byte[] payload, boolean syncsafe) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        int n = payload.length;
        if (syncsafe) {
            o.write((n >>> 21) & 0x7F);
            o.write((n >>> 14) & 0x7F);
            o.write((n >>> 7) & 0x7F);
            o.write(n & 0x7F);
        } else {
            o.write(n >>> 24);
            o.write(n >>> 16);
            o.write(n >>> 8);
            o.write(n);
        }
        o.write(0);
        o.write(0);
        o.writeBytes(payload);
        return o.toByteArray();
    }

    private static byte[] tag(int major, byte[]... frames) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] f : frames) {
            body.writeBytes(f);
        }
        int n = body.size();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes("ID3".getBytes(StandardCharsets.ISO_8859_1));
        o.write(major);
        o.write(0);
        o.write(0);
        o.write((n >>> 21) & 0x7F);
        o.write((n >>> 14) & 0x7F);
        o.write((n >>> 7) & 0x7F);
        o.write(n & 0x7F);
        o.writeBytes(body.toByteArray());
        return o.toByteArray();
    }

    private static byte[] textUtf8(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(3);
        o.writeBytes(s.getBytes(StandardCharsets.UTF_8));
        return o.toByteArray();
    }

    private static byte[] textLatin1(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(0);
        o.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
        return o.toByteArray();
    }

    private static byte[] textUtf16(String s) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(1);
        o.write(0xFF);
        o.write(0xFE);
        o.writeBytes(s.getBytes(StandardCharsets.UTF_16LE));
        return o.toByteArray();
    }

    private Path write(String name, byte[] head, byte[] tail) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(head);
        o.writeBytes(AUDIO);
        o.writeBytes(tail);
        return Files.write(dir.resolve(name), o.toByteArray());
    }

    @Test
    void readsId3v24TextFramesAndAttachedPicture() throws Exception {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        ByteArrayOutputStream apic = new ByteArrayOutputStream();
        apic.write(0);
        apic.writeBytes("image/png".getBytes(StandardCharsets.ISO_8859_1));
        apic.write(0);
        apic.write(3);
        apic.write(0);
        apic.writeBytes(png);
        Path f = write("v24.mp3", tag(4,
                frame("TIT2", textUtf8("Héllo ünicode"), true),
                frame("TPE1", textUtf8("Artist"), true),
                frame("TALB", textUtf8("Album"), true),
                frame("TRCK", textUtf8("3/12"), true),
                frame("TDRC", textUtf8("2021-05-01"), true),
                frame("APIC", apic.toByteArray(), true)), new byte[0]);

        Id3Reader.Result r = Id3Reader.read(f);
        TrackMetadata m = r.metadata();
        assertEquals("Héllo ünicode", m.title());
        assertEquals("Artist", m.artist());
        assertEquals("Album", m.album());
        assertEquals(3, m.trackNumber());
        assertEquals(2021, m.year());
        assertTrue(m.hasCoverArt());
        assertEquals("image/png", r.coverArt().mimeType());
        assertArrayEquals(png, r.coverArt().data());

        Optional<CoverArt> art = Id3Reader.readCoverArt(f);
        assertTrue(art.isPresent());
        assertArrayEquals(png, art.get().data());
    }

    @Test
    void readsId3v23UnicodeFramesWithBigEndianSizes() throws Exception {
        Path f = write("v23.mp3", tag(3,
                frame("TIT2", textUtf16("Song"), false),
                frame("TYER", textLatin1("1999"), false),
                frame("TPOS", textLatin1("1/2"), false)), new byte[0]);
        TrackMetadata m = Id3Reader.read(f).metadata();
        assertEquals("Song", m.title());
        assertEquals(1999, m.year());
        assertEquals(1, m.discNumber());
        assertFalse(m.hasCoverArt());
        assertNull(m.artist());
    }

    @Test
    void v23UnsynchronizationPreservesLiteralFf00BeforeOrdinaryBytes() throws Exception {
        byte[] image = {(byte) 0xFF, 0x00, 0x01, 0x7F};
        ByteArrayOutputStream apic = new ByteArrayOutputStream();
        apic.write(0);
        apic.writeBytes("image/jpeg".getBytes(StandardCharsets.ISO_8859_1));
        apic.write(0);
        apic.write(3);
        apic.write(0);
        apic.writeBytes(image);

        byte[] id3 = tag(3, frame("APIC", apic.toByteArray(), false));
        id3[5] = (byte) 0x80; // tag-level ID3v2.3 unsynchronisation flag
        Path f = write("v23-unsync-literal.mp3", id3, new byte[0]);

        CoverArt cover = Id3Reader.read(f).coverArt();
        assertNotNull(cover, "literal FF 00 bytes must not be mistaken for an unsynchronisation escape");
        assertArrayEquals(image, cover.data());
    }

    @Test
    void readsId3v1TrailerWhenNoV2TagIsPresent() throws Exception {
        ByteArrayOutputStream v1 = new ByteArrayOutputStream();
        v1.writeBytes("TAG".getBytes(StandardCharsets.ISO_8859_1));
        v1.writeBytes(padded("Old Title", 30));
        v1.writeBytes(padded("Old Artist", 30));
        v1.writeBytes(padded("Old Album", 30));
        v1.writeBytes(padded("1987", 4));
        v1.writeBytes(padded("comment", 30));
        v1.write(17);
        Path f = write("v1.mp3", new byte[0], v1.toByteArray());
        TrackMetadata m = Id3Reader.read(f).metadata();
        assertEquals("Old Title", m.title());
        assertEquals("Old Artist", m.artist());
        assertEquals("Old Album", m.album());
        assertEquals(1987, m.year());
    }

    private static byte[] padded(String s, int n) {
        byte[] out = new byte[n];
        byte[] src = s.getBytes(StandardCharsets.ISO_8859_1);
        System.arraycopy(src, 0, out, 0, Math.min(n, src.length));
        return out;
    }

    @Test
    void untaggedFileYieldsEmptyMetadata() throws Exception {
        Path f = Files.write(dir.resolve("plain.mp3"), AUDIO);
        assertEquals(TrackMetadata.EMPTY, Id3Reader.read(f).metadata());
        assertTrue(Id3Reader.readCoverArt(f).isEmpty());
    }

    @Test
    void oversizedFrameDoesNotThrowAndKeepsEarlierFields() throws Exception {
        byte[] good = frame("TIT2", textUtf8("Kept"), true);
        byte[] bad = frame("TPE1", textUtf8("Lost"), true);
        // Declare a frame size far beyond the end of the tag.
        bad[4] = 0x7F;
        bad[5] = 0x7F;
        bad[6] = 0x7F;
        bad[7] = 0x7F;
        Path f = write("bad.mp3", tag(4, good, bad), new byte[0]);
        assertDoesNotThrow(() -> Id3Reader.read(f));
        assertEquals("Kept", Id3Reader.read(f).metadata().title());
    }
}
