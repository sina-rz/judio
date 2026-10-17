package io.github.sinaraz.internal.ogg;

/** A complete Ogg packet reconstructed from the segment table and any continuation pages. */
record OggPacket(long packetNumber, byte[] data, boolean beginningOfStream,
                        boolean endOfStream, long granulePosition) {

    public OggPacket {
        if (packetNumber < 0) {
            throw new IllegalArgumentException("packetNumber must not be negative");
        }
        if (data == null) {
            throw new NullPointerException("data");
        }
    }
}
