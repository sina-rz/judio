package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OggPageProbeTest {

    @Test
    void bisectsLargeOggByGranuleAndUsesActualByteOffsets() throws IOException {
        int serial = 0x1234_5678;
        List<byte[]> pages = new ArrayList<>();
        pages.add(OggTestPages.page(serial, 0, 0x02, 0, List.of(OggTestPages.packet(128, 1))));
        pages.add(OggTestPages.page(serial, 1, 0, 0, List.of(OggTestPages.packet(128, 2))));

        long expectedOffset = -1;
        for (int sequence = 2; sequence <= 6; sequence++) {
            long granule = (sequence - 1L) * 1_000;
            if (granule == 4_000) {
                expectedOffset = pages.stream().mapToLong(page -> page.length).sum();
            }
            pages.add(OggTestPages.page(serial, sequence, 0, granule,
                    List.of(OggTestPages.packet(60_000, sequence))));
        }
        pages.add(OggTestPages.page(serial, 7, 0x04, 5_000, List.of(OggTestPages.packet(256, 7))));
        byte[] data = OggTestPages.concat(pages.toArray(byte[][]::new));
        assertTrue(data.length > 256 * 1024, "fixture should require range-fragment bisection");

        List<Long> requestedOffsets = new ArrayList<>();
        OggPageProbe.PageHeader selected = OggPageProbe.findPageAtOrBefore(offset -> {
            requestedOffsets.add(offset);
            return new ByteArrayInputStream(data, Math.toIntExact(offset), data.length - Math.toIntExact(offset));
        }, data.length, serial, 4_500);

        assertEquals(expectedOffset, selected.byteOffset());
        assertEquals(4_000, selected.granulePosition());
        assertEquals(5, selected.sequenceNumber());
        OggPageProbe.PageHeader eos = OggPageProbe.findLastEos(offset -> {
            requestedOffsets.add(offset);
            return new ByteArrayInputStream(data, Math.toIntExact(offset), data.length - Math.toIntExact(offset));
        }, data.length, serial);
        assertEquals(7, eos.sequenceNumber());
        assertEquals(5_000, eos.granulePosition());
        assertTrue(requestedOffsets.stream().anyMatch(offset -> offset > 0),
                "the page probe should request a nonzero byte range in a large file");
    }

    @Test
    void missingEosIsOneBoundedTailProbeRatherThanAFullBackwardsScan() throws IOException {
        byte[] bytes = new byte[4 * 1024 * 1024];
        List<Long> offsets = new ArrayList<>();
        assertNull(OggPageProbe.findLastEos(offset -> {
            offsets.add(offset);
            return new ByteArrayInputStream(bytes, Math.toIntExact(offset), bytes.length - Math.toIntExact(offset));
        }, bytes.length, 123));
        assertEquals(List.of(bytes.length - 256L * 1024), offsets);
    }

    @Test
    void durationProbeRejectsChainingTrailingOtherStreamsAndBadChecksums() throws IOException {
        int serial = 123;
        byte[] first = OggTestPages.page(serial, 0, 0x02, 0, List.of(new byte[] {1}));
        byte[] eos = OggTestPages.page(serial, 1, 0x04, 1000, List.of(new byte[] {2}));
        byte[] valid = OggTestPages.concat(first, eos);
        assertEquals(1000, OggPageProbe.findLastEos(offset -> new ByteArrayInputStream(valid,
                Math.toIntExact(offset), valid.length - Math.toIntExact(offset)), valid.length, serial, first.length)
                .granulePosition());

        byte[] reusedSerial = OggTestPages.concat(valid, valid);
        assertNull(OggPageProbe.findLastEos(offset -> new ByteArrayInputStream(reusedSerial,
                Math.toIntExact(offset), reusedSerial.length - Math.toIntExact(offset)),
                reusedSerial.length, serial, first.length));

        byte[] otherEos = OggTestPages.page(456, 3, 0x04, 10_000, List.of(new byte[] {3}));
        byte[] trailing = OggTestPages.concat(valid, otherEos);
        assertNull(OggPageProbe.findLastEos(offset -> new ByteArrayInputStream(trailing,
                Math.toIntExact(offset), trailing.length - Math.toIntExact(offset)),
                trailing.length, serial, first.length));

        byte[] corrupt = valid.clone();
        corrupt[corrupt.length - 1] ^= 1;
        assertNull(OggPageProbe.findLastEos(offset -> new ByteArrayInputStream(corrupt,
                Math.toIntExact(offset), corrupt.length - Math.toIntExact(offset)),
                corrupt.length, serial, first.length));
    }

    @Test
    void seeksBracketWithContinuedPagesButOnlyReturnSafePacketBoundaries() throws IOException {
        int serial = 42;
        List<byte[]> pages = new ArrayList<>();
        List<Long> safeOffsets = new ArrayList<>();
        List<Long> safeGranules = new ArrayList<>();
        pages.add(OggTestPages.page(serial, 0, 0x02, 0, List.of(new byte[] {1})));
        long offset = pages.getFirst().length;
        long sequence = 1;
        for (int i = 1; i <= 60; i++) {
            if (i % 5 == 0) {
                byte[] lacing = new byte[240];
                java.util.Arrays.fill(lacing, (byte) 255);
                byte[] partial = OggTestPages.pageWithLacing(serial, sequence++, 0, -1,
                        lacing, OggTestPages.packet(240 * 255, i));
                pages.add(partial);
                offset += partial.length;
                byte[] continued = OggTestPages.page(serial, sequence++, 0x01, i * 1000L,
                        List.of(OggTestPages.packet(128, i)));
                pages.add(continued);
                offset += continued.length;
            } else {
                safeOffsets.add(offset);
                safeGranules.add(i * 1000L);
                byte[] page = OggTestPages.page(serial, sequence++, 0, i * 1000L,
                        List.of(OggTestPages.packet(60_000, i)));
                pages.add(page);
                offset += page.length;
            }
        }
        pages.add(OggTestPages.page(serial, sequence, 0x04, 60_000, List.of(new byte[] {0})));
        byte[] bytes = OggTestPages.concat(pages.toArray(byte[][]::new));
        for (long target : new long[] {1500, 5500, 19_500, 25_500, 40_500, 59_500, 60_000}) {
            int expected = 0;
            while (expected + 1 < safeGranules.size() && safeGranules.get(expected + 1) <= target) {
                expected++;
            }
            OggPageProbe.PageHeader anchor = OggPageProbe.findPageAtOrBefore(pos -> new ByteArrayInputStream(bytes,
                    Math.toIntExact(pos), bytes.length - Math.toIntExact(pos)), bytes.length, serial, target);
            assertEquals(safeOffsets.get(expected).longValue(), anchor.byteOffset(), "target " + target);
            assertEquals(safeGranules.get(expected).longValue(), anchor.granulePosition());
        }
    }

}
