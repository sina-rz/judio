package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayOutputStream;
import java.util.List;

import io.github.sinaraz.error.CorruptFileException;

/** Stateful packet decoder and granule adapter for one logical Ogg Opus or Vorbis stream. */
final class OggStreamDecoder implements AutoCloseable {

    private static final int MAX_PAGE_PCM_BYTES = 16 * 1024 * 1024;

    private final String source;
    private final OggHeaderParser headers;
    private OggPacketDecoder packetDecoder;
    private OggGranuleTracker granules;
    private boolean hasAudio;
    private boolean ended;

    OggStreamDecoder(String source) {
        this.source = source == null ? "Ogg stream" : source;
        this.headers = new OggHeaderParser(this.source);
    }

    /** Recreates codec state from the already validated headers, without file or network I/O. */
    OggStreamDecoder(String source, List<OggPage> headerPages) {
        this(source);
        try {
            for (OggPage page : headerPages) {
                processHeaderPage(page);
            }
            if (!headers.ready()) {
                throw new IllegalArgumentException("cached Ogg headers are incomplete");
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    List<OggPage> headerPages() {
        return headers.headerPages();
    }

    /** Processes the complete packets on one physical page and returns trimmed PCM16LE for that page. */
    byte[] processPage(OggPage page) {
        if (ended) {
            throw corrupt("page received after Ogg logical-stream EOS");
        }
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        long decodedFrames = 0;
        long audioPackets = 0;
        long lastPacketFrames = 0;

        for (OggPacket packet : page.packets()) {
            OggHeaderParser.PacketRole role = headers.accept(page, packet);
            if (role == OggHeaderParser.PacketRole.UNSUPPORTED || role == OggHeaderParser.PacketRole.HEADER) {
                if (headers.ready() && packetDecoder == null) {
                    initializePacketDecoder(false);
                }
                continue;
            }
            if (packetDecoder == null || granules == null) {
                throw corrupt("audio packet arrived before codec headers were complete");
            }
            byte[] packetPcm = packetDecoder.decode(packet);
            int frameSize = packetDecoder.channels() * 2;
            if (packetPcm.length % frameSize != 0) {
                throw corrupt("codec decoder returned a partial PCM frame");
            }
            long packetFrames = packetPcm.length / frameSize;
            decodedFrames = add(decodedFrames, packetFrames, "Ogg page sample count overflow");
            audioPackets++;
            lastPacketFrames = packetFrames;
            if (packetPcm.length > MAX_PAGE_PCM_BYTES - pcm.size()) {
                throw corrupt("decoded Ogg page exceeds the PCM safety limit");
            }
            pcm.writeBytes(packetPcm);
        }

        if (page.endOfStream() && headers.codec() != null
                && headers.codec() != OggHeaderParser.Codec.UNKNOWN && !headers.ready()) {
            throw corrupt("Ogg stream ended before codec headers were complete");
        }
        if (audioPackets > 0) {
            OggGranuleTracker.Adjustment adjustment = granules.apply(page.granulePosition(), page.endOfStream(),
                    decodedFrames, audioPackets, lastPacketFrames);
            byte[] pageBytes = pcm.toByteArray();
            byte[] adjusted = trimPage(pageBytes, decodedFrames, adjustment,
                    packetDecoder.channels() * 2);
            hasAudio = true;
            if (page.endOfStream()) {
                ended = true;
            }
            return adjusted;
        }

        if (page.endOfStream()) {
            if (granules != null) {
                granules.apply(page.granulePosition(), true, 0, 0, 0);
            }
            if (headers.codec() != null && headers.codec() != OggHeaderParser.Codec.UNKNOWN && !hasAudio) {
                throw corrupt("Ogg audio stream contains no audio packets");
            }
            ended = true;
        }
        return new byte[0];
    }

    /** Primes codec headers without retaining any audio that shares a page with the last header. */
    boolean processHeaderPage(OggPage page) {
        if (hasAudio) {
            throw new IllegalStateException("range-seek header decoder has already processed audio");
        }
        boolean audioPacketSeen = false;
        for (OggPacket packet : page.packets()) {
            OggHeaderParser.PacketRole role = headers.accept(page, packet);
            if (role == OggHeaderParser.PacketRole.AUDIO) {
                audioPacketSeen = true;
                continue;
            }
            if (headers.ready() && packetDecoder == null) {
                initializePacketDecoder(false);
            }
        }
        if (page.endOfStream() && supported() && !headers.ready()) {
            throw corrupt("Ogg stream ended before range-seek codec headers were complete");
        }
        return audioPacketSeen;
    }

    /** Resets granule accounting for the first audio page reached by a range seek. */
    void prepareForRangeStart(boolean firstAudioPage) {
        if (packetDecoder == null || !headers.ready()) {
            throw new IllegalStateException("range-seek codec headers are incomplete");
        }
        granules = new OggGranuleTracker(headers.info().codec(), headers.info().preSkip(), !firstAudioPage);
        hasAudio = false;
        ended = false;
    }

    private void initializePacketDecoder(boolean rangeStart) {
        OggCodecInfo info = headers.info();
        packetDecoder = switch (info.codec()) {
            case OPUS -> new OpusPacketDecoder(info);
            case VORBIS -> new VorbisPacketDecoder(headers);
            case UNKNOWN -> throw new AssertionError("unsupported codec cannot be ready");
        };
        granules = new OggGranuleTracker(info.codec(), info.preSkip(), rangeStart);
    }

    boolean supported() {
        return headers.codec() != null && headers.codec() != OggHeaderParser.Codec.UNKNOWN;
    }

    boolean headersReady() {
        return headers.ready();
    }

    boolean ended() {
        return ended;
    }

    boolean hasAudio() {
        return hasAudio;
    }

    OggCodecInfo info() {
        return headers.info();
    }

    long outputFrames() {
        return granules == null ? 0 : granules.totalOutputFrames();
    }

    long initialOriginFrames() {
        return granules == null ? 0 : granules.initialOriginFrames();
    }

    private static byte[] trimPage(byte[] page, long decodedFrames, OggGranuleTracker.Adjustment adjustment,
                                   int frameSize) {
        long expectedBytes = decodedFrames * frameSize;
        if (expectedBytes != page.length) {
            throw new CorruptFileException("read", "codec PCM length does not match decoded frame count", null, null);
        }
        int startBytes = Math.multiplyExact(adjustment.trimStartFrames(), frameSize);
        int endBytes = Math.multiplyExact(adjustment.trimEndFrames(), frameSize);
        if (startBytes < 0 || endBytes < 0 || startBytes + endBytes > page.length) {
            throw new CorruptFileException("read", "Ogg granule trim exceeds decoded PCM", null, null);
        }
        int length = page.length - startBytes - endBytes;
        byte[] result = new byte[length];
        System.arraycopy(page, startBytes, result, 0, length);
        if (length / frameSize != adjustment.outputFrames()) {
            throw new CorruptFileException("read", "Ogg granule output count is inconsistent", null, null);
        }
        return result;
    }

    private CorruptFileException corrupt(String message) {
        return new CorruptFileException("read", message + " (" + source + ")", null, null);
    }

    private static long add(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new CorruptFileException("read", message, null, e);
        }
    }

    @Override
    public void close() {
        if (packetDecoder != null) {
            packetDecoder.close();
            packetDecoder = null;
        }
        headers.close();
    }
}
