package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.github.sinaraz.error.CorruptFileException;

/**
 * Incremental, codec-agnostic reader for Ogg pages and packets.
 *
 * <p>Each call reads and validates one complete page. Packets are reassembled across continuation
 * pages and returned together with the page that completes them; the page granule position is
 * attached only to its last completed packet. No codec assumptions are made here.
 *
 * <p>Corrupt pages fail closed. This reader does not attempt resynchronization after a bad checksum
 * or sequence number, so callers never receive PCM assembled across an undetected hole.
 */
final class OggPacketReader implements AutoCloseable {

    /** Maximum packet size accepted from untrusted input. Vorbis setup headers fit comfortably below it. */
    static final int MAX_PACKET_BYTES = 16 * 1024 * 1024;
    /** Maximum number of simultaneously open logical streams. */
    private static final int MAX_ACTIVE_STREAMS = 64;
    private static final int MAX_TOTAL_BUFFERED_PACKET_BYTES = 32 * 1024 * 1024;
    private static final int FIXED_HEADER_BYTES = 27;
    private static final int CHECKSUM_OFFSET = 22;
    private static final long UNSIGNED_INT_MASK = 0xFFFF_FFFFL;
    private static final int[] CRC_TABLE = makeCrcTable();

    private final InputStream input;
    private final String source;
    private final int maxPacketBytes;
    private final Integer rangeSerial;
    private final Map<Integer, LogicalStream> streams = new HashMap<>();
    private long bufferedPacketBytes;
    private long byteOffset;
    private long nextStreamId;
    private boolean eof;
    private boolean closed;
    private boolean rangeStartPending;

    /**
     * Creates a streaming Ogg reader. Closing the reader closes {@code input}.
     *
     * @param input  source beginning at the first Ogg page
     * @param source short diagnostic label, or {@code "Ogg stream"}
     */
    OggPacketReader(InputStream input, String source) {
        this(input, source, MAX_PACKET_BYTES, 0L, null);
    }

    OggPacketReader(InputStream input, String source, int maxPacketBytes) {
        this(input, source, maxPacketBytes, 0L, null);
    }

    /** Creates a range reader that starts on a validated page of the selected serial mid-stream. */
    OggPacketReader(InputStream input, String source, long byteOffset, int rangeSerial) {
        this(input, source, MAX_PACKET_BYTES, byteOffset, rangeSerial);
    }

    private OggPacketReader(InputStream input, String source, int maxPacketBytes,
                            long initialOffset, Integer rangeSerial) {
        this.input = Objects.requireNonNull(input, "input");
        this.source = source == null || source.isBlank() ? "Ogg stream" : source;
        if (maxPacketBytes < 1 || maxPacketBytes > MAX_PACKET_BYTES) {
            throw new IllegalArgumentException("maxPacketBytes must be between 1 and " + MAX_PACKET_BYTES);
        }
        if (initialOffset < 0) {
            throw new IllegalArgumentException("initialOffset must not be negative");
        }
        this.maxPacketBytes = maxPacketBytes;
        this.rangeSerial = rangeSerial;
        this.rangeStartPending = rangeSerial != null;
        this.byteOffset = initialOffset;
    }

    /**
     * Reads the next complete page, or returns {@code null} at clean physical EOF.
     * A truncated header, segment table, body, or continued packet is corruption.
     */
    OggPage readPage() throws IOException {
        ensureOpen();
        while (true) {
            if (eof) {
                return null;
            }

            long pageOffset = byteOffset;
            int first = input.read();
            if (first < 0) {
                eof = true;
                for (LogicalStream stream : streams.values()) {
                    if (stream.partial.size() != 0) {
                        throw corrupt("unexpected end of input in a continued packet at byte " + pageOffset);
                    }
                }
                return null;
            }
            byte[] header = new byte[FIXED_HEADER_BYTES];
            header[0] = (byte) first;
            byteOffset++;
            readFully(header, 1, FIXED_HEADER_BYTES - 1, "page header");

            if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
                throw corrupt("invalid Ogg capture pattern at byte " + pageOffset);
            }
            if ((header[4] & 0xFF) != 0) {
                throw corrupt("unsupported Ogg page version " + (header[4] & 0xFF) + " at byte " + pageOffset);
            }
            int flags = header[5] & 0xFF;
            if ((flags & ~0x07) != 0) {
                throw corrupt("reserved Ogg page flags are set at byte " + pageOffset);
            }
            boolean continued = (flags & 0x01) != 0;
            boolean bos = (flags & 0x02) != 0;
            boolean eos = (flags & 0x04) != 0;
            long granule = readLe64(header, 6);
            if (granule < 0 && granule != -1L) {
                throw corrupt("invalid negative Ogg granule position at byte " + pageOffset);
            }
            int serial = readLe32(header, 14);
            long sequence = readLe32Unsigned(header, 18);
            long expectedCrc = readLe32Unsigned(header, CHECKSUM_OFFSET);
            int segmentCount = header[26] & 0xFF;
            byte[] lacing = new byte[segmentCount];
            readFully(lacing, 0, lacing.length, "segment table");

            int bodyLength = 0;
            for (byte value : lacing) {
                bodyLength = Math.addExact(bodyLength, value & 0xFF);
            }
            byte[] body = new byte[bodyLength];
            readFully(body, 0, body.length, "page body");
            long actualCrc = pageCrc(header, lacing, body);
            if (actualCrc != expectedCrc) {
                throw corrupt("Ogg checksum mismatch at byte " + pageOffset + " (serial "
                        + Integer.toUnsignedString(serial) + ", sequence " + sequence + ")");
            }

            if (rangeSerial != null && serial != rangeSerial) {
                continue;
            }
            LogicalStream stream = streams.get(serial);
            if (stream == null) {
                if (rangeSerial != null && rangeStartPending && !bos) {
                    if (continued) {
                        throw corrupt("range seek starts on a continued Ogg page at byte " + pageOffset);
                    }
                    stream = new LogicalStream(nextStreamId++, serial);
                    stream.nextSequence = sequence;
                    stream.packetNumber = 1L; // the first packet here is not the logical-stream BOS packet
                    streams.put(serial, stream);
                    rangeStartPending = false;
                } else {
                    if (!bos) {
                        throw corrupt("logical stream does not begin with a BOS page at byte " + pageOffset);
                    }
                    if (sequence != 0) {
                        throw corrupt("first page sequence is not zero at byte " + pageOffset);
                    }
                    if (streams.size() >= MAX_ACTIVE_STREAMS) {
                        throw corrupt("too many simultaneously active Ogg logical streams");
                    }
                    stream = new LogicalStream(nextStreamId++, serial);
                    streams.put(serial, stream);
                    rangeStartPending = false;
                }
            } else if (bos) {
                throw corrupt("duplicate BOS page before logical-stream EOS at byte " + pageOffset);
            }
            if (sequence != stream.nextSequence) {
                throw corrupt("Ogg page sequence discontinuity at byte " + pageOffset + ": expected "
                        + stream.nextSequence + " but got " + sequence);
            }
            stream.nextSequence = (sequence + 1L) & UNSIGNED_INT_MASK;

            boolean hasPartialPacket = stream.partial.size() != 0;
            if (continued != hasPartialPacket) {
                throw corrupt("Ogg continuation flag does not match packet state at byte " + pageOffset);
            }
            if (continued && segmentCount == 0) {
                throw corrupt("continued Ogg page has no lacing segments at byte " + pageOffset);
            }

            List<PendingPacket> packets = new ArrayList<>();
            int bodyOffset = 0;
            for (byte laceByte : lacing) {
                int lace = laceByte & 0xFF;
                if (lace > maxPacketBytes - stream.partial.size()) {
                    throw corrupt("Ogg packet exceeds the " + maxPacketBytes + " byte safety limit");
                }
                if (lace > MAX_TOTAL_BUFFERED_PACKET_BYTES - bufferedPacketBytes) {
                    throw corrupt("Ogg packet buffering exceeds the " + MAX_TOTAL_BUFFERED_PACKET_BYTES + " byte safety limit");
                }
                stream.partial.write(body, bodyOffset, lace);
                bufferedPacketBytes += lace;
                bodyOffset += lace;
                if (lace < 255) {
                    long packetNumber = stream.packetNumber++;
                    byte[] packetData = stream.partial.toByteArray();
                    packets.add(new PendingPacket(packetNumber, packetData));
                    bufferedPacketBytes -= packetData.length;
                    stream.partial.reset();
                }
            }
            if (eos && stream.partial.size() != 0) {
                throw corrupt("EOS page ends with an incomplete Ogg packet at byte " + pageOffset);
            }
            if (packets.isEmpty() && !eos && granule != -1L) {
                throw corrupt("Ogg page without a completed packet has a granule position at byte " + pageOffset);
            }

            List<OggPacket> completePackets = new ArrayList<>(packets.size());
            for (int i = 0; i < packets.size(); i++) {
                PendingPacket packet = packets.get(i);
                boolean last = i == packets.size() - 1;
                completePackets.add(new OggPacket(
                        packet.number(), packet.data(), packet.number() == 0,
                        eos && last, last ? granule : -1L));
            }

            long streamId = stream.id;
            if (eos) {
                streams.remove(serial);
            }
            return new OggPage(streamId, serial, sequence, granule, pageOffset,
                    continued, bos, eos, completePackets);
        }
    }

    /** Current physical byte position, useful for diagnostics and local indexing. */
    long byteOffset() {
        return byteOffset;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            streams.clear();
            input.close();
        }
    }

    private void readFully(byte[] destination, int offset, int length, String what) throws IOException {
        int completed = 0;
        while (completed < length) {
            int count = input.read(destination, offset + completed, length - completed);
            if (count < 0) {
                throw corrupt("truncated Ogg " + what + " at byte " + byteOffset);
            }
            if (count == 0) {
                int next = input.read();
                if (next < 0) {
                    throw corrupt("truncated Ogg " + what + " at byte " + byteOffset);
                }
                destination[offset + completed++] = (byte) next;
                byteOffset++;
            } else {
                completed += count;
                byteOffset += count;
            }
        }
    }

    private CorruptFileException corrupt(String message) {
        return new CorruptFileException("read", message + " (" + source + ")", null, null);
    }

    private void ensureOpen() throws IOException {
        if (closed) {
            throw new IOException("Ogg page reader is closed");
        }
    }

    static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | (data[offset + 3] << 24);
    }

    static long readLe32Unsigned(byte[] data, int offset) {
        return Integer.toUnsignedLong(readLe32(data, offset));
    }

    static long readLe64(byte[] data, int offset) {
        return readLe32Unsigned(data, offset) | (readLe32Unsigned(data, offset + 4) << 32);
    }

    static long pageCrc(byte[] header, byte[] lacing, byte[] body) {
        int crc = 0;
        for (int i = 0; i < header.length; i++) {
            int value = i >= CHECKSUM_OFFSET && i < CHECKSUM_OFFSET + 4 ? 0 : header[i] & 0xFF;
            crc = updateCrc(crc, value);
        }
        for (byte value : lacing) {
            crc = updateCrc(crc, value & 0xFF);
        }
        for (byte value : body) {
            crc = updateCrc(crc, value & 0xFF);
        }
        return Integer.toUnsignedLong(crc);
    }

    private static int updateCrc(int crc, int value) {
        return (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ value) & 0xFF];
    }

    private static int[] makeCrcTable() {
        int[] table = new int[256];
        for (int i = 0; i < table.length; i++) {
            int remainder = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                remainder = (remainder & 0x8000_0000) != 0
                        ? (remainder << 1) ^ 0x04C1_1DB7
                        : remainder << 1;
            }
            table[i] = remainder;
        }
        return table;
    }

    private static final class LogicalStream {
        private final long id;
        private final ByteArrayOutputStream partial = new ByteArrayOutputStream(4096);
        private long nextSequence;
        private long packetNumber;

        private LogicalStream(long id, int serial) {
            this.id = id;
            this.nextSequence = 0;
        }
    }

    private record PendingPacket(long number, byte[] data) {}
}
