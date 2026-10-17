package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
