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


    /** Reads at most one tail window; missing EOS is not a reason to download the entire stream. */
    static PageHeader findLastEos(LongFunction<InputStream> rangeOpener, long contentLength, int serial)
            throws IOException {
        return findLastEos(rangeOpener, contentLength, serial, -1L);
    }

    /**
     * With a known first audio offset, only accept a terminal EOS for that logical stream. Chained
     * streams seen in the tail are deliberately left with unknown duration, not a last-link duration.
     */
    static PageHeader findLastEos(LongFunction<InputStream> rangeOpener, long contentLength, int serial,
                                  long firstAudioOffset) throws IOException {
        if (contentLength <= 0) {
            return null;
        }
        long start = Math.max(0L, contentLength - SCAN_BYTES);
        List<PageHeader> pages = scan(rangeOpener, start, (int) (contentLength - start));
        PageHeader lastEos = null;
        for (PageHeader page : pages) {
            if (firstAudioOffset >= 0 && page.beginningOfStream() && page.byteOffset() > firstAudioOffset) {
                return null;
            }
            if (page.serialNumber() == serial && page.endOfStream() && page.granulePosition() >= 0) {
                if (firstAudioOffset >= 0 && lastEos != null) {
                    return null;
                }
                lastEos = page;
            }
        }
        if (firstAudioOffset >= 0 && lastEos != null
                && lastEos.byteOffset() + lastEos.pageLength() != contentLength) {
            return null;
        }
        return lastEos;
    }

    /** Returns a packet-aligned audio page whose end granule is at or before the preroll target. */
    static PageHeader findPageAtOrBefore(LongFunction<InputStream> rangeOpener, long contentLength,
                                         int serial, long granuleTarget) throws IOException {
        if (contentLength <= 0 || granuleTarget < 0) {
            return null;
        }
        long low = 0L;
        long high = contentLength;
        PageHeader best = null;
        for (int attempt = 0; attempt < MAX_BISECTIONS && high - low > SCAN_BYTES; attempt++) {
            long probeOffset = low + ((high - low) >>> 1);
            List<PageHeader> pages = scan(rangeOpener, probeOffset,
                    (int) Math.min(SCAN_BYTES, contentLength - probeOffset));
            long advance = low;
            boolean safeFloorSeen = false;
            PageHeader ceiling = null;
            for (PageHeader page : pages) {
                // Continued and EOS pages still bracket time even though they are not safe places
                // to initialize the codec. Ignoring them can jump past the desired seek interval.
                if (page.serialNumber() != serial || page.granulePosition() <= 0 || page.beginningOfStream()) {
                    continue;
                }
                if (page.granulePosition() > granuleTarget) {
                    ceiling = page;
                    break;
                }
                advance = page.byteOffset() + page.pageLength();
                if (isSeekPoint(page, serial, granuleTarget)
                        && (best == null || page.byteOffset() > best.byteOffset())) {
                    best = page;
                    safeFloorSeen = true;
                }
            }
            if (advance > low && safeFloorSeen) {
                low = advance;
                if (ceiling != null) {
                    high = Math.max(low, ceiling.byteOffset());
                }
            } else {
                // If this logical stream is sparse, prefer an earlier, safe anchor over skipping
                // unknown audio. The caller can always fall back to its cached first audio page.
                high = probeOffset;
            }
        }

        // Search the remaining interval, not the window after the old best point. Include room
        // for a page that starts just before the interval ends but crosses the fragment boundary.
        int finalBytes = (int) Math.min(SCAN_BYTES + (long) MAX_PAGE_BYTES, contentLength - low);
        for (PageHeader page : scan(rangeOpener, low, finalBytes)) {
            if (isSeekPoint(page, serial, granuleTarget)
                    && (best == null || page.byteOffset() > best.byteOffset())) {
                best = page;
            }
        }
        return best;
    }

    private static boolean isSeekPoint(PageHeader page, int serial, long granuleTarget) {
        return page.serialNumber() == serial && page.granulePosition() > 0
                && page.granulePosition() <= granuleTarget
                && !page.continued() && !page.beginningOfStream() && !page.endOfStream();
    }

    private static List<PageHeader> scan(LongFunction<InputStream> rangeOpener, long startOffset, int maxBytes)
            throws IOException {
        if (maxBytes <= 0) {
            return List.of();
        }
        byte[] bytes;
        try (InputStream input = rangeOpener.apply(startOffset)) {
            if (input == null) {
                throw new IOException("Ogg range opener returned no input stream");
            }
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
                offset++;
                continue;
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
