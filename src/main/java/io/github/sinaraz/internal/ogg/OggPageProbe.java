package io.github.sinaraz.internal.ogg;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongFunction;

/** Finds checksum-verified Ogg page boundaries in HTTP byte-range fragments. */
final class OggPageProbe {

    private static final int SCAN_BYTES = 256 * 1024;
    private static final int MAX_BISECTIONS = 32;
    private static final int MAX_PAGE_BYTES = 27 + 255 + 255 * 255;

    private OggPageProbe() {}

    static PageHeader findLastEos(LongFunction<InputStream> rangeOpener, long contentLength, int serial)
            throws IOException {
        long end = contentLength;
        while (end > 0) {
            long start = Math.max(0, end - SCAN_BYTES);
            int requestBytes = (int) (end - start);
            List<PageHeader> pages = scan(rangeOpener, start, requestBytes);
            PageHeader lastEos = null;
            for (PageHeader page : pages) {
                if (page.serialNumber() == serial && page.endOfStream() && page.granulePosition() >= 0) {
                    lastEos = page;
                }
            }
            if (lastEos != null || start == 0) {
                return lastEos;
            }
            end = start + MAX_PAGE_BYTES;
        }
        return null;
    }

    /** Returns the last complete audio page at or before {@code granuleTarget}. */
    static PageHeader findPageAtOrBefore(LongFunction<InputStream> rangeOpener, long contentLength,
                                         int serial, long granuleTarget) throws IOException {
        if (contentLength <= 0 || granuleTarget < 0) {
            return null;
        }
        long low = 0;
        long high = contentLength;
        PageHeader best = null;
        for (int attempt = 0; attempt < MAX_BISECTIONS && low < high; attempt++) {
            if (high - low <= SCAN_BYTES) {
                break;
            }
            long probeOffset = low + ((high - low) >>> 1);
            List<PageHeader> pages = scan(rangeOpener, probeOffset,
                    (int) Math.min(SCAN_BYTES, contentLength - probeOffset));
            PageHeader floor = null;
            PageHeader ceiling = null;
            for (PageHeader page : pages) {
                if (page.serialNumber() != serial || page.granulePosition() <= 0
                        || page.continued() || page.beginningOfStream() || page.endOfStream()) {
                    continue;
                }
                if (page.granulePosition() <= granuleTarget) {
                    floor = page;
                } else {
                    ceiling = page;
                    break;
                }
            }
            if (floor != null) {
                best = floor;
                low = floor.byteOffset() + 1;
                if (ceiling != null) {
                    high = ceiling.byteOffset();
                }
            } else if (ceiling != null) {
                high = ceiling.byteOffset();
            } else {
                // The selected logical stream can be sparse in a multiplexed file. Move through
                // this range fragment rather than repeatedly probing the same bytes.
                long advance = pages.isEmpty() ? SCAN_BYTES
                        : Math.max(1, pages.getLast().byteOffset() - probeOffset + pages.getLast().pageLength());
                low = probeOffset + Math.min(contentLength - probeOffset, advance);
            }
        }

        long finalStart = best == null ? 0 : best.byteOffset();
        long finalBytes = Math.min(SCAN_BYTES * 2L, contentLength - finalStart);
        List<PageHeader> tail = scan(rangeOpener, finalStart, (int) finalBytes);
        for (PageHeader page : tail) {
            if (page.serialNumber() == serial && page.granulePosition() > 0
                    && !page.continued() && !page.beginningOfStream() && !page.endOfStream()
                    && page.granulePosition() <= granuleTarget) {
                best = page;
            }
        }
        return best;
    }

    /** Returns the first complete audio page for a serial, skipping its BOS and codec-header pages. */
    static PageHeader findFirstAudioPage(LongFunction<InputStream> rangeOpener, long contentLength, int serial)
            throws IOException {
        long offset = 0;
        while (offset < contentLength) {
            int requestBytes = (int) Math.min(SCAN_BYTES, contentLength - offset);
            List<PageHeader> pages = scan(rangeOpener, offset, requestBytes);
            for (PageHeader page : pages) {
                if (page.serialNumber() == serial && page.granulePosition() > 0
                        && !page.continued() && !page.beginningOfStream() && !page.endOfStream()) {
                    return page;
                }
            }
            if (pages.isEmpty()) {
                offset += requestBytes;
            } else {
                PageHeader last = pages.getLast();
                long next = last.byteOffset() + last.pageLength();
                offset = next > offset ? next : offset + requestBytes;
            }
        }
        return null;
    }

    private static List<PageHeader> scan(LongFunction<InputStream> rangeOpener, long startOffset, int maxBytes)
            throws IOException {
        if (maxBytes <= 0) {
            return List.of();
        }
        byte[] bytes;
        try (InputStream input = rangeOpener.apply(startOffset)) {
            bytes = input.readNBytes(maxBytes);
        }
        List<PageHeader> pages = new ArrayList<>();
        int offset = 0;
        while (offset + 27 <= bytes.length) {
            if (bytes[offset] != 'O' || bytes[offset + 1] != 'g'
                    || bytes[offset + 2] != 'g' || bytes[offset + 3] != 'S'
                    || (bytes[offset + 4] & 0xFF) != 0) {
                offset++;
                continue;
            }
            int flags = bytes[offset + 5] & 0xFF;
            if ((flags & ~0x07) != 0) {
                offset++;
                continue;
            }
            int segmentCount = bytes[offset + 26] & 0xFF;
            int tableEnd = offset + 27 + segmentCount;
            if (tableEnd > bytes.length) {
                break;
            }
            byte[] header = new byte[27];
            System.arraycopy(bytes, offset, header, 0, header.length);
            byte[] lacing = new byte[segmentCount];
            System.arraycopy(bytes, offset + 27, lacing, 0, segmentCount);
            int bodyLength = 0;
            for (byte lace : lacing) {
                bodyLength += lace & 0xFF;
            }
            int pageLength = 27 + segmentCount + bodyLength;
            int pageEnd = offset + pageLength;
            if (pageEnd > bytes.length || pageLength > MAX_PAGE_BYTES) {
                offset++;
                continue;
            }
            byte[] body = new byte[bodyLength];
            System.arraycopy(bytes, tableEnd, body, 0, bodyLength);
            long expectedCrc = OggPacketReader.readLe32Unsigned(header, 22);
            if (OggPacketReader.pageCrc(header, lacing, body) != expectedCrc) {
                offset++;
                continue;
            }
            long granule = OggPacketReader.readLe64(header, 6);
            if (granule < 0 && granule != -1L) {
                offset += pageLength;
                continue;
            }
            int serial = OggPacketReader.readLe32(header, 14);
            long sequence = OggPacketReader.readLe32Unsigned(header, 18);
            pages.add(new PageHeader(startOffset + offset, pageLength, serial, sequence, granule,
                    (flags & 0x01) != 0, (flags & 0x02) != 0, (flags & 0x04) != 0));
            offset = pageEnd;
        }
        return pages;
    }

    record PageHeader(long byteOffset, int pageLength, int serialNumber, long sequenceNumber,
                      long granulePosition, boolean continued, boolean beginningOfStream,
                      boolean endOfStream) {}
}
