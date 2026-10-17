package io.github.sinaraz.internal.ogg;

import java.util.List;

/** One validated physical Ogg page and the complete packets that end on it. */
record OggPage(long streamId, int serialNumber, long sequenceNumber, long granulePosition,
                      long byteOffset, boolean continued, boolean beginningOfStream,
                      boolean endOfStream, List<OggPacket> packets) {

    public OggPage {
        if (streamId < 0) {
            throw new IllegalArgumentException("streamId must not be negative");
        }
        if (byteOffset < 0) {
            throw new IllegalArgumentException("byteOffset must not be negative");
        }
        packets = List.copyOf(packets);
    }
}
