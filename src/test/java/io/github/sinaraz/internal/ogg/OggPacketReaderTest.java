package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.error.CorruptFileException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggPacketReaderTest {

    @Test
    void readsMultiplePacketsAndLacingBoundariesFromOnePage() throws Exception {
        byte[] empty = new byte[0];
        byte[] one = {0x5A};
        byte[] exactSegment = OggTestPages.packet(255, 3);
        byte[] multipleSegments = OggTestPages.packet(256, 9);
        byte[] encoded = OggTestPages.page(0x12345678, 0, 0x06, 42,
                List.of(empty, one, exactSegment, multipleSegments));

        try (OggPacketReader reader = reader(encoded)) {
            OggPage page = reader.readPage();
            assertEquals(0x12345678, page.serialNumber());
            assertEquals(0, page.sequenceNumber());
            assertEquals(42, page.granulePosition());
            assertTrue(page.beginningOfStream());
            assertTrue(page.endOfStream());
            assertEquals(4, page.packets().size());
            assertArrayEquals(empty, page.packets().get(0).data());
            assertArrayEquals(one, page.packets().get(1).data());
            assertArrayEquals(exactSegment, page.packets().get(2).data());
            assertArrayEquals(multipleSegments, page.packets().get(3).data());
            assertEquals(0, page.packets().get(0).packetNumber());
            assertTrue(page.packets().get(0).beginningOfStream());
            assertFalse(page.packets().get(0).endOfStream());
            assertEquals(-1, page.packets().get(0).granulePosition());
            assertTrue(page.packets().get(3).endOfStream());
            assertEquals(42, page.packets().get(3).granulePosition());
            assertNull(reader.readPage());
        }
    }

    @Test
    void reconstructsPacketContinuedAcrossPagesAndHonorsZeroTerminator() throws Exception {
        byte[] packet = OggTestPages.packet(300, 23);
        byte[] first = OggTestPages.pageWithLacing(7, 0, 0x02, -1,
                new byte[] {(byte) 255}, java.util.Arrays.copyOfRange(packet, 0, 255));
        byte[] second = OggTestPages.pageWithLacing(7, 1, 0x05, 300,
                new byte[] {45, 0}, new byte[] {packet[255], packet[256], packet[257], packet[258], packet[259],
                        packet[260], packet[261], packet[262], packet[263], packet[264], packet[265], packet[266],
                        packet[267], packet[268], packet[269], packet[270], packet[271], packet[272], packet[273],
                        packet[274], packet[275], packet[276], packet[277], packet[278], packet[279], packet[280],
                        packet[281], packet[282], packet[283], packet[284], packet[285], packet[286], packet[287],
                        packet[288], packet[289], packet[290], packet[291], packet[292], packet[293], packet[294],
                        packet[295], packet[296], packet[297], packet[298], packet[299]});

        try (OggPacketReader reader = reader(OggTestPages.concat(first, second))) {
            OggPage start = reader.readPage();
            assertTrue(start.packets().isEmpty());
            assertEquals(-1, start.granulePosition());
            OggPage finish = reader.readPage();
            assertEquals(2, finish.packets().size());
            assertArrayEquals(packet, finish.packets().get(0).data());
            assertFalse(finish.packets().get(0).endOfStream());
            assertEquals(-1, finish.packets().get(0).granulePosition());
            assertArrayEquals(new byte[0], finish.packets().get(1).data());
            assertTrue(finish.packets().get(1).endOfStream());
            assertEquals(300, finish.packets().get(1).granulePosition());
        }
    }

    @Test
    void readsShortReadsAndZeroLengthReadsWithoutLosingBytes() throws Exception {
        byte[] expected = OggTestPages.page(12, 0, 0x06, 1, List.of(new byte[] {3, 4, 5}));
        InputStream choppy = new FilterInputStream(new ByteArrayInputStream(expected)) {
            private int calls;

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (length == 0) {
                    return 0;
                }
                if (calls++ % 4 == 0) {
                    return 0;
                }
                return super.read(buffer, offset, Math.min(3, length));
            }
        };
        try (OggPacketReader reader = new OggPacketReader(choppy, "short read test")) {
            assertArrayEquals(new byte[] {3, 4, 5}, reader.readPage().packets().get(0).data());
            assertNull(reader.readPage());
        }
    }

    @Test
    void keepsInterleavedLogicalStreamsSeparate() throws Exception {
        byte[] encoded = OggTestPages.concat(
                OggTestPages.page(11, 0, 0x02, 0, List.of(new byte[] {1})),
                OggTestPages.page(22, 0, 0x02, 0, List.of(new byte[] {2})),
                OggTestPages.page(11, 1, 0x04, 1, List.of(new byte[] {3})),
                OggTestPages.page(22, 1, 0x04, 1, List.of(new byte[] {4})));
        try (OggPacketReader reader = reader(encoded)) {
            OggPage a = reader.readPage();
            OggPage b = reader.readPage();
            OggPage aEnd = reader.readPage();
            OggPage bEnd = reader.readPage();
            assertEquals(a.streamId(), aEnd.streamId());
            assertEquals(b.streamId(), bEnd.streamId());
            assertNotEquals(a.streamId(), b.streamId());
            assertEquals(11, aEnd.serialNumber());
            assertEquals(22, bEnd.serialNumber());
        }
    }

    @Test
    void aSerialCanBeReusedAfterEosForAChainedLogicalStream() throws Exception {
        byte[] encoded = OggTestPages.concat(
                OggTestPages.page(8, 0, 0x02, 0, List.of(new byte[] {1})),
                OggTestPages.page(8, 1, 0x04, 1, List.of(new byte[] {2})),
                OggTestPages.page(8, 0, 0x02, 0, List.of(new byte[] {3})),
                OggTestPages.page(8, 1, 0x04, 1, List.of(new byte[] {4})));
        try (OggPacketReader reader = reader(encoded)) {
            long firstId = reader.readPage().streamId();
            long firstEnd = reader.readPage().streamId();
            long secondId = reader.readPage().streamId();
            long secondEnd = reader.readPage().streamId();
            assertEquals(firstId, firstEnd);
            assertEquals(secondId, secondEnd);
            assertNotEquals(firstId, secondId);
        }
    }

    @Test
    void acceptsAnEmptyEosPageAfterCompletedPackets() throws Exception {
        byte[] encoded = OggTestPages.concat(
                OggTestPages.page(9, 0, 0x02, 0, List.of(new byte[] {1})),
                OggTestPages.page(9, 1, 0x04, 0, List.of()));
        try (OggPacketReader reader = reader(encoded)) {
            reader.readPage();
            OggPage eos = reader.readPage();
            assertTrue(eos.endOfStream());
            assertTrue(eos.packets().isEmpty());
            assertEquals(0, eos.granulePosition());
        }
    }

    @Test
    void rejectsBadCaptureVersionFlagsChecksumAndSequence() throws Exception {
        byte[] valid = OggTestPages.page(4, 0, 0x06, 1, List.of(new byte[] {1}));
        byte[] badCapture = valid.clone();
        badCapture[0] = 'X';
        assertThrows(CorruptFileException.class, () -> readOne(badCapture));

        byte[] badVersion = valid.clone();
        badVersion[4] = 1;
        fixChecksum(badVersion);
        assertThrows(CorruptFileException.class, () -> readOne(badVersion));

        byte[] badFlags = valid.clone();
        badFlags[5] = (byte) 0x86;
        fixChecksum(badFlags);
        assertThrows(CorruptFileException.class, () -> readOne(badFlags));

        byte[] badCrc = valid.clone();
        badCrc[badCrc.length - 1] ^= 1;
        assertThrows(CorruptFileException.class, () -> readOne(badCrc));

        byte[] gap = OggTestPages.concat(
                OggTestPages.page(5, 0, 0x02, 0, List.of(new byte[] {1})),
                OggTestPages.page(5, 2, 0x04, 1, List.of(new byte[] {2})));
        try (OggPacketReader reader = reader(gap)) {
            reader.readPage();
            assertThrows(CorruptFileException.class, reader::readPage);
        }
    }

    @Test
    void rejectsBrokenContinuationAndUnexpectedEof() throws Exception {
        byte[] start = OggTestPages.pageWithLacing(6, 0, 0x02, -1,
                new byte[] {(byte) 255}, OggTestPages.packet(255, 0));
        byte[] missingContinuation = OggTestPages.pageWithLacing(6, 1, 0, 1,
                new byte[] {1}, new byte[] {7});
        try (OggPacketReader reader = reader(OggTestPages.concat(start, missingContinuation))) {
            reader.readPage();
            assertThrows(CorruptFileException.class, reader::readPage);
        }

        byte[] noPartial = OggTestPages.page(6, 0, 0x02, 0, List.of(new byte[] {1}));
        byte[] falseContinuation = OggTestPages.pageWithLacing(6, 1, 0x01, 1,
                new byte[] {0}, new byte[0]);
        try (OggPacketReader reader = reader(OggTestPages.concat(noPartial, falseContinuation))) {
            reader.readPage();
            assertThrows(CorruptFileException.class, reader::readPage);
        }

        try (OggPacketReader reader = reader(start)) {
            reader.readPage();
            assertThrows(CorruptFileException.class, reader::readPage);
        }
    }

    @Test
    void rejectsTruncatedHeaderSegmentTableAndBody() throws Exception {
        assertThrows(CorruptFileException.class, () -> readOne(new byte[] {'O', 'g', 'g', 'S'}));

        byte[] page = OggTestPages.page(7, 0, 0x06, 1, List.of(new byte[] {1, 2, 3}));
        assertThrows(CorruptFileException.class,
                () -> readOne(java.util.Arrays.copyOf(page, 27 + 1)));
        assertThrows(CorruptFileException.class,
                () -> readOne(java.util.Arrays.copyOf(page, page.length - 1)));
    }

    @Test
    void rejectsInvalidGranuleAndOversizedPackets() throws Exception {
        byte[] invalidGranule = OggTestPages.pageWithLacing(2, 0, 0x02, 1,
                new byte[] {(byte) 255}, OggTestPages.packet(255, 1));
        assertThrows(CorruptFileException.class, () -> readOne(invalidGranule));

        byte[] oversized = OggTestPages.page(2, 0, 0x06, 1,
                List.of(OggTestPages.packet(12, 2)));
        try (OggPacketReader reader = new OggPacketReader(new ByteArrayInputStream(oversized), "bound test", 10)) {
            assertThrows(CorruptFileException.class, reader::readPage);
        }
    }

    @Test
    void completedLargePacketReleasesItsAccumulatorCapacity() throws Exception {
        byte[] packet = new byte[100_000];
        new Random(0x0A66L).nextBytes(packet);

        int firstBodyLength = 255 * 255;
        byte[] firstLacing = new byte[255];
        java.util.Arrays.fill(firstLacing, (byte) 255);
        byte[] firstPage = OggTestPages.pageWithLacing(91, 0, 0x02, -1,
                firstLacing, java.util.Arrays.copyOfRange(packet, 0, firstBodyLength));

        int remaining = packet.length - firstBodyLength;
        int fullSegments = remaining / 255;
        int finalSegment = remaining % 255;
        byte[] secondLacing = new byte[fullSegments + 1];
        java.util.Arrays.fill(secondLacing, 0, fullSegments, (byte) 255);
        secondLacing[fullSegments] = (byte) finalSegment;
        byte[] secondPage = OggTestPages.pageWithLacing(91, 1, 0x01, 0,
                secondLacing, java.util.Arrays.copyOfRange(packet, firstBodyLength, packet.length));

        try (OggPacketReader reader = reader(OggTestPages.concat(firstPage, secondPage))) {
            assertTrue(reader.readPage().packets().isEmpty());
            assertEquals(64 * 1024, reader.allocatedPacketCapacityBytes());

            OggPage completed = reader.readPage();
            assertEquals(1, completed.packets().size());
            assertArrayEquals(packet, completed.packets().getFirst().data());
            assertEquals(4 * 1024, reader.allocatedPacketCapacityBytes(),
                    "large completed packets must not pin their growth buffers for the stream lifetime");
        }
    }

    @Test
    void emptyInputIsCleanPhysicalEof() throws Exception {
        try (OggPacketReader reader = reader(new byte[0])) {
            assertNull(reader.readPage());
            assertNull(reader.readPage());
        }
    }

    @Test
    void malformedRandomInputsFailPromptlyWithoutUnboundedAllocation() {
        Random random = new Random(0x0A66L);
        for (int i = 0; i < 200; i++) {
            byte[] bytes = new byte[random.nextInt(512)];
            random.nextBytes(bytes);
            try (OggPacketReader reader = reader(bytes)) {
                try {
                    reader.readPage();
                } catch (IOException | RuntimeException expected) {
                    // Invalid random pages must be rejected as I/O/corruption, not fatal JVM errors.
                }
            } catch (IOException expected) {
                // Closing an adversarial input is allowed to report I/O failure.
            }
        }
    }

    private static OggPacketReader reader(byte[] input) {
        return new OggPacketReader(new ByteArrayInputStream(input), "test Ogg");
    }

    private static OggPage readOne(byte[] input) throws IOException {
        try (OggPacketReader reader = reader(input)) {
            return reader.readPage();
        }
    }

    private static void fixChecksum(byte[] page) {
        page[22] = 0;
        page[23] = 0;
        page[24] = 0;
        page[25] = 0;
        int crc = 0;
        for (byte b : page) {
            crc ^= (b & 0xFF) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000_0000) != 0
                        ? (crc << 1) ^ 0x04C1_1DB7
                        : crc << 1;
            }
        }
        page[22] = (byte) crc;
        page[23] = (byte) (crc >>> 8);
        page[24] = (byte) (crc >>> 16);
        page[25] = (byte) (crc >>> 24);
    }
}
