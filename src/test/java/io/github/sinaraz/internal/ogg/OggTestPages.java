package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Small independent Ogg page builder for parser tests; the CRC is computed bit-by-bit. */
final class OggTestPages {

    private OggTestPages() {}

    static byte[] page(int serial, long sequence, int flags, long granule, List<byte[]> packets) {
        List<Integer> lacing = new ArrayList<>();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (byte[] packet : packets) {
            int offset = 0;
            while (packet.length - offset >= 255) {
                lacing.add(255);
                body.write(packet, offset, 255);
                offset += 255;
            }
            int remaining = packet.length - offset;
            lacing.add(remaining);
            if (remaining > 0) {
                body.write(packet, offset, remaining);
            }
        }
        byte[] lacingBytes = new byte[lacing.size()];
        for (int i = 0; i < lacing.size(); i++) {
            lacingBytes[i] = (byte) (int) lacing.get(i);
        }
        return pageWithLacing(serial, sequence, flags, granule, lacingBytes, body.toByteArray());
    }

    static byte[] pageWithLacing(int serial, long sequence, int flags, long granule,
                                 byte[] lacing, byte[] body) {
        if (lacing.length > 255) {
            throw new IllegalArgumentException("more than 255 lacing values");
        }
        byte[] page = new byte[27 + lacing.length + body.length];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[4] = 0;
        page[5] = (byte) flags;
        putLe64(page, 6, granule);
        putLe32(page, 14, serial);
        putLe32(page, 18, (int) sequence);
        putLe32(page, 22, 0);
        page[26] = (byte) lacing.length;
        System.arraycopy(lacing, 0, page, 27, lacing.length);
        System.arraycopy(body, 0, page, 27 + lacing.length, body.length);
        putLe32(page, 22, crc(page));
        return page;
    }

    static byte[] concat(byte[]... arrays) {
        int length = 0;
        for (byte[] array : arrays) {
            length = Math.addExact(length, array.length);
        }
        byte[] joined = new byte[length];
        int offset = 0;
        for (byte[] array : arrays) {
            System.arraycopy(array, 0, joined, offset, array.length);
            offset += array.length;
        }
        return joined;
    }

    /** Builds a longer single logical stream using real codec packets, with independently counted granules. */
    static RepeatedAudio repeatAudio(byte[] input, int repetitions, int packetsPerPage) throws IOException {
        OggHeaderParser headers = new OggHeaderParser("repeated test audio");
        try {
            List<byte[]> headerPackets = new ArrayList<>();
            List<OggPacket> audio = new ArrayList<>();
            int serial = 0;
            try (OggPacketReader reader = new OggPacketReader(new ByteArrayInputStream(input), "test audio")) {
                OggPage page;
                while ((page = reader.readPage()) != null) {
                    serial = page.serialNumber();
                    for (OggPacket packet : page.packets()) {
                        OggHeaderParser.PacketRole role = headers.accept(page, packet);
                        if (role == OggHeaderParser.PacketRole.HEADER) {
                            headerPackets.add(packet.data());
                        } else if (role == OggHeaderParser.PacketRole.AUDIO) {
                            audio.add(packet);
                        }
                    }
                }
            }
            List<byte[]> pages = new ArrayList<>();
            pages.add(page(serial, 0, 0x02, 0, List.of(headerPackets.getFirst())));
            pages.add(page(serial, 1, 0, 0, headerPackets.subList(1, headerPackets.size())));
            List<byte[]> packets = new ArrayList<>();
            long granule = 0L;
            long sequence = 2L;
            int previousBlock = -1;
            for (int repetition = 0; repetition < repetitions; repetition++) {
                for (int i = 0; i < audio.size(); i++) {
                    OggPacket packet = audio.get(i);
                    if (headers.codec() == OggHeaderParser.Codec.OPUS) {
                        granule += OggPacketDuration.opusSamples(packet.data());
                    } else {
                        int block = OggPacketDuration.vorbisBlockSize(headers.vorbisInfo(), packet);
                        granule += previousBlock < 0 ? 0 : (previousBlock + block) / 4L;
                        previousBlock = block;
                    }
                    packets.add(packet.data());
                    boolean last = repetition == repetitions - 1 && i == audio.size() - 1;
                    if (packets.size() == packetsPerPage || last) {
                        pages.add(page(serial, sequence++, last ? 0x04 : 0, granule, packets));
                        packets = new ArrayList<>();
                    }
                }
            }
            OggCodecInfo info = headers.info();
            long frames = granule - (info.codec() == OggHeaderParser.Codec.OPUS ? info.preSkip() : 0L);
            return new RepeatedAudio(concat(pages.toArray(byte[][]::new)), frames, info.sampleRate(), info.channels());
        } finally {
            headers.close();
        }
    }

    record RepeatedAudio(byte[] bytes, long frames, int sampleRate, int channels) {}

    static byte[] withOpusPreSkip(byte[] input, int preSkip) {
        byte[] result = input.clone();
        int body = 27 + (result[26] & 0xFF);
        result[body + 10] = (byte) preSkip;
        result[body + 11] = (byte) (preSkip >>> 8);
        fixChecksum(result, 0, pageLength(result, 0));
        return result;
    }

    static byte[] withSerial(byte[] input, int serial) {
        byte[] result = input.clone();
        int offset = 0;
        while (offset < result.length) {
            int length = pageLength(result, offset);
            putLe32(result, offset + 14, serial);
            fixChecksum(result, offset, length);
            offset += length;
        }
        return result;
    }

    static byte[] shiftPositiveGranules(byte[] input, long shift) {
        byte[] shifted = input.clone();
        int offset = 0;
        while (offset < shifted.length) {
            int length = pageLength(shifted, offset);
            long granule = readLe64(shifted, offset + 6);
            if (granule > 0) {
                putLe64(shifted, offset + 6, Math.addExact(granule, shift));
                fixChecksum(shifted, offset, length);
            }
            offset += length;
        }
        return shifted;
    }

    static VorbisOrigin withValidVorbisInitialOrigin(byte[] input, long origin) throws IOException {
        if (origin <= 0) {
            throw new IllegalArgumentException("origin must be positive");
        }
        OggHeaderParser headers = new OggHeaderParser("Vorbis origin test");
        List<OggPacket> headerPackets = new ArrayList<>();
        List<OggPacket> audioPackets = new ArrayList<>();
        int serial = 0;
        try (OggPacketReader reader = new OggPacketReader(new ByteArrayInputStream(input), "Vorbis origin test")) {
            OggPage page;
            boolean firstPage = true;
            while ((page = reader.readPage()) != null) {
                if (firstPage) {
                    serial = page.serialNumber();
                    firstPage = false;
                }
                for (OggPacket packet : page.packets()) {
                    OggHeaderParser.PacketRole role = headers.accept(page, packet);
                    if (role == OggHeaderParser.PacketRole.HEADER) {
                        headerPackets.add(packet);
                    } else if (role == OggHeaderParser.PacketRole.AUDIO) {
                        audioPackets.add(packet);
                    }
                }
            }
        }
        if (headers.codec() != OggHeaderParser.Codec.VORBIS || !headers.ready()
                || headerPackets.size() != 3 || audioPackets.size() < 2) {
            throw new IllegalArgumentException("input is not a complete Vorbis stream with audio packets");
        }

        List<byte[]> pages = new ArrayList<>();
        pages.add(page(serial, 0, 0x02, 0, List.of(headerPackets.getFirst().data())));
        pages.add(page(serial, 1, 0, 0, List.of(headerPackets.get(1).data(), headerPackets.get(2).data())));

        long decodedFrames = 0;
        int previousBlockSize = -1;
        List<byte[]> firstAudioPage = new ArrayList<>(2);
        for (int i = 0; i < 2; i++) {
            OggPacket packet = audioPackets.get(i);
            int blockSize = OggPacketDuration.vorbisBlockSize(headers.vorbisInfo(), packet);
            if (previousBlockSize >= 0) {
                decodedFrames = Math.addExact(decodedFrames, (previousBlockSize + blockSize) / 4L);
            }
            previousBlockSize = blockSize;
            firstAudioPage.add(packet.data());
        }
        long granule = Math.addExact(decodedFrames, origin);
        long sequence = 2;
        boolean firstPageIsEos = audioPackets.size() == 2;
        pages.add(page(serial, sequence++, firstPageIsEos ? 0x04 : 0, granule, firstAudioPage));
        long previousGranule = granule;

        int packetOffset = 2;
        while (packetOffset < audioPackets.size()) {
            int count = Math.min(32, audioPackets.size() - packetOffset);
            List<byte[]> pagePackets = new ArrayList<>(count);
            long pageFrames = 0;
            for (int i = 0; i < count; i++) {
                OggPacket packet = audioPackets.get(packetOffset++);
                int blockSize = OggPacketDuration.vorbisBlockSize(headers.vorbisInfo(), packet);
                pageFrames = Math.addExact(pageFrames, (previousBlockSize + blockSize) / 4L);
                decodedFrames = Math.addExact(decodedFrames, (previousBlockSize + blockSize) / 4L);
                previousBlockSize = blockSize;
                pagePackets.add(packet.data());
            }
            previousGranule = Math.addExact(previousGranule, pageFrames);
            boolean eos = packetOffset == audioPackets.size();
            pages.add(page(serial, sequence++, eos ? 0x04 : 0, previousGranule, pagePackets));
        }
        return new VorbisOrigin(concat(pages.toArray(byte[][]::new)), decodedFrames);
    }

    record VorbisOrigin(byte[] bytes, long decodedFrames) {}

    static byte[] withAddedComment(byte[] input, String text) throws IOException {
        List<PageSpec> pages = new ArrayList<>();
        boolean updated = false;
        try (OggPacketReader reader = new OggPacketReader(new ByteArrayInputStream(input), "comment test")) {
            OggPage page;
            while ((page = reader.readPage()) != null) {
                if (page.continued()) {
                    throw new IllegalArgumentException("test fixture contains a continued page");
                }
                List<byte[]> packets = new ArrayList<>();
                for (OggPacket packet : page.packets()) {
                    byte[] data = packet.data();
                    if (!updated && packet.packetNumber() == 1) {
                        data = appendComment(data, text);
                        updated = true;
                    }
                    packets.add(data);
                }
                int flags = (page.beginningOfStream() ? 0x02 : 0) | (page.endOfStream() ? 0x04 : 0);
                pages.add(new PageSpec(page.serialNumber(), page.sequenceNumber(), flags,
                        page.granulePosition(), packets));
            }
        }
        if (!updated) {
            throw new IllegalArgumentException("fixture contains no comment packet");
        }
        List<byte[]> rebuilt = new ArrayList<>(pages.size());
        for (PageSpec page : pages) {
            rebuilt.add(page(page.serial(), page.sequence(), page.flags(), page.granule(), page.packets()));
        }
        return concat(rebuilt.toArray(byte[][]::new));
    }

    private static byte[] appendComment(byte[] packet, String text) throws IOException {
        int prefix;
        if (startsWith(packet, "OpusTags".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            prefix = 8;
        } else if (startsWith(packet, new byte[] {3, 'v', 'o', 'r', 'b', 'i', 's'})) {
            prefix = 7;
        } else {
            throw new IllegalArgumentException("second Ogg packet is not a supported comment header");
        }
        int vendorLength = readLe32(packet, prefix);
        if (vendorLength < 0 || prefix + 4L + vendorLength + 4L > packet.length) {
            throw new IllegalArgumentException("invalid comment header vendor field");
        }
        int countOffset = prefix + 4 + vendorLength;
        int count = readLe32(packet, countOffset);
        byte[] comment = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(packet.length + comment.length + 4);
        out.write(packet, 0, countOffset);
        writeLe32(out, Math.addExact(count, 1));
        out.write(packet, countOffset + 4, packet.length - countOffset - 4);
        writeLe32(out, comment.length);
        out.write(comment);
        return out.toByteArray();
    }

    private static boolean startsWith(byte[] bytes, byte[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static void writeLe32(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
        out.write(value >>> 16);
        out.write(value >>> 24);
    }

    private static int readLe32(byte[] bytes, int offset) {
        if (offset < 0 || offset + 4 > bytes.length) {
            throw new IllegalArgumentException("truncated 32-bit field");
        }
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16) | (bytes[offset + 3] << 24);
    }

    private record PageSpec(int serial, long sequence, int flags, long granule, List<byte[]> packets) {}

    static byte[] dropLastPage(byte[] input) {
        int offset = 0;
        int lastOffset = -1;
        while (offset < input.length) {
            lastOffset = offset;
            offset += pageLength(input, offset);
        }
        if (lastOffset < 0) {
            throw new IllegalArgumentException("input has no Ogg pages");
        }
        return java.util.Arrays.copyOf(input, lastOffset);
    }

    static byte[] packet(int length, int seed) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            data[i] = (byte) (seed + i);
        }
        return data;
    }

    private static int crc(byte[] page) {
        return crc(page, 0, page.length);
    }

    private static int crc(byte[] page, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            int field = i >= 22 && i < 26 ? 0 : page[offset + i] & 0xFF;
            crc ^= field << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000_0000) != 0
                        ? (crc << 1) ^ 0x04C1_1DB7
                        : crc << 1;
            }
        }
        return crc;
    }

    private static void fixChecksum(byte[] data, int offset, int length) {
        putLe32(data, offset + 22, 0);
        putLe32(data, offset + 22, crc(data, offset, length));
    }

    private static int pageLength(byte[] data, int offset) {
        if (offset < 0 || offset + 27 > data.length || data[offset] != 'O' || data[offset + 1] != 'g'
                || data[offset + 2] != 'g' || data[offset + 3] != 'S') {
            throw new IllegalArgumentException("invalid or truncated Ogg page");
        }
        int segments = data[offset + 26] & 0xFF;
        if (offset + 27 + segments > data.length) {
            throw new IllegalArgumentException("truncated Ogg segment table");
        }
        int body = 0;
        for (int i = 0; i < segments; i++) {
            body += data[offset + 27 + i] & 0xFF;
        }
        int length = 27 + segments + body;
        if (offset + length > data.length) {
            throw new IllegalArgumentException("truncated Ogg page body");
        }
        return length;
    }

    private static long readLe64(byte[] data, int offset) {
        long low = (data[offset] & 0xFFL) | ((data[offset + 1] & 0xFFL) << 8)
                | ((data[offset + 2] & 0xFFL) << 16) | ((data[offset + 3] & 0xFFL) << 24);
        long high = (data[offset + 4] & 0xFFL) | ((data[offset + 5] & 0xFFL) << 8)
                | ((data[offset + 6] & 0xFFL) << 16) | ((data[offset + 7] & 0xFFL) << 24);
        return low | (high << 32);
    }

    private static void putLe32(byte[] target, int offset, int value) {
        target[offset] = (byte) value;
        target[offset + 1] = (byte) (value >>> 8);
        target[offset + 2] = (byte) (value >>> 16);
        target[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLe64(byte[] target, int offset, long value) {
        putLe32(target, offset, (int) value);
        putLe32(target, offset + 4, (int) (value >>> 32));
    }
}
