package io.github.sinaraz.internal.ogg;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.Info;

import io.github.sinaraz.error.CorruptFileException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.TrackMetadata;

/** Parses mandatory Opus or Vorbis headers without taking ownership of Ogg page parsing. */
final class OggHeaderParser {

    enum Codec { OPUS, VORBIS, UNKNOWN }
    enum PacketRole { HEADER, AUDIO, UNSUPPORTED }

    private static final byte[] OPUS_HEAD = "OpusHead".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] VORBIS_ID = {1, 'v', 'o', 'r', 'b', 'i', 's'};
    private static final int MAX_HEADER_PACKET_BYTES = 8 * 1024 * 1024;

    private final String source;
    private final List<OggPage> headerPages = new ArrayList<>(3);
    private Codec codec;
    private int headerCount;
    private OpusHead opusHead;
    private Info vorbisInfo;
    private Comment vorbisComment;
    private TrackMetadata metadata = TrackMetadata.EMPTY;
    private OggCodecInfo codecInfo;
    private boolean closed;

    OggHeaderParser(String source) {
        this.source = source == null ? "Ogg stream" : source;
    }

    PacketRole accept(OggPage page, OggPacket packet) {
        if (closed) {
            throw new IllegalStateException("Ogg header parser is closed");
        }
        if (codec == Codec.UNKNOWN) {
            return PacketRole.UNSUPPORTED;
        }
        if (codec != null && !ready() && packet.data().length > MAX_HEADER_PACKET_BYTES) {
            throw corrupt("codec header packet exceeds the " + MAX_HEADER_PACKET_BYTES + " byte safety limit");
        }
        if (codec != null && ready()) {
            return PacketRole.AUDIO;
        }
        if (codec == null) {
            if (!packet.beginningOfStream()) {
                throw corrupt("first Ogg codec packet is not marked as the beginning of its stream");
            }
            byte[] data = packet.data();
            if (startsWith(data, OPUS_HEAD)) {
                if (data.length > MAX_HEADER_PACKET_BYTES) {
                    throw corrupt("Opus identification header exceeds the safety limit");
                }
                codec = Codec.OPUS;
                parseOpusHead(data);
                if (!page.beginningOfStream() || page.endOfStream() || page.granulePosition() != 0
                        || page.packets().size() != 1) {
                    throw corrupt("Opus identification header must be alone on a zero-granule BOS page");
                }
            } else if (startsWith(data, VORBIS_ID)) {
                if (data.length > MAX_HEADER_PACKET_BYTES) {
                    throw corrupt("Vorbis identification header exceeds the safety limit");
                }
                codec = Codec.VORBIS;
                vorbisInfo = new Info();
                vorbisInfo.init();
                vorbisComment = new Comment();
                vorbisComment.init();
                if (!page.beginningOfStream() || page.endOfStream() || page.granulePosition() != 0
                        || page.packets().size() != 1) {
                    throw corrupt("Vorbis identification header must be alone on a zero-granule BOS page");
                }
                submitVorbisHeader(packet);
            } else {
                codec = Codec.UNKNOWN;
                return PacketRole.UNSUPPORTED;
            }
            headerCount = 1;
            return rememberHeader(page, packet);
        }

        if (page.endOfStream()) {
            throw corrupt("Ogg audio stream ended before all codec headers were complete");
        }
        if (page.granulePosition() != 0) {
            throw corrupt("codec header packet does not end on a zero-granule page");
        }
        if (packet.packetNumber() != headerCount) {
            throw corrupt("unexpected packet number in Ogg codec headers");
        }
        if (codec == Codec.OPUS) {
            if (headerCount != 1 || page.packets().size() != 1) {
                throw corrupt("OpusTags must be the only packet on its header page");
            }
            metadata = OggCommentParser.parseOpusTags(packet.data());
            headerCount++;
            codecInfo = new OggCodecInfo(codec, 48_000, opusHead.channels(), opusHead.preSkip(),
                    opusHead.outputGain(), opusHead.mappingFamily(), opusHead.streamCount(),
                    opusHead.coupledStreamCount(), opusHead.channelMapping(), metadata);
        } else {
            if (headerCount >= 3) {
                throw corrupt("unexpected Vorbis header packet");
            }
            submitVorbisHeader(packet);
            if (headerCount == 1) {
                metadata = OggCommentParser.parseVorbisComment(packet.data());
            }
            headerCount++;
            if (headerCount == 3) {
                int channels = vorbisInfo.channels;
                int rate = vorbisInfo.rate;
                if (rate <= 0) {
                    throw corrupt("Vorbis identification header has an invalid sample rate");
                }
                if (channels < 1 || channels > 2) {
                    throw new UnsupportedAudioFormatException("open",
                            "Ogg Vorbis stream has " + channels + " channels; Judio supports mono and stereo",
                            null, null);
                }
                codecInfo = new OggCodecInfo(codec, rate, channels, 0, 0,
                        -1, 0, 0, null, metadata);
            }
        }
        return rememberHeader(page, packet);
    }

    /** Only the two or three codec headers are retained, never audio packets sharing their page. */
    private PacketRole rememberHeader(OggPage page, OggPacket packet) {
        headerPages.add(new OggPage(page.streamId(), page.serialNumber(), page.sequenceNumber(),
                page.granulePosition(), page.byteOffset(), page.continued(), page.beginningOfStream(),
                page.endOfStream(), List.of(packet)));
        return PacketRole.HEADER;
    }

    List<OggPage> headerPages() {
        return List.copyOf(headerPages);
    }

    boolean ready() {
        return codecInfo != null;
    }

    Codec codec() {
        return codec;
    }

    OggCodecInfo info() {
        if (!ready()) {
            throw new IllegalStateException("Ogg codec headers are incomplete");
        }
        return codecInfo;
    }

    Info vorbisInfo() {
        if (codec != Codec.VORBIS || !ready()) {
            throw new IllegalStateException("Vorbis setup header is incomplete");
        }
        return vorbisInfo;
    }

    private void parseOpusHead(byte[] data) {
        if (data.length < 19) {
            throw corrupt("truncated Opus identification header");
        }
        int version = data[8] & 0xFF;
        if (version > 15) {
            throw new UnsupportedAudioFormatException("open",
                    "Ogg Opus mapping version " + version + " is not compatible with RFC 7845", null, null);
        }
        int channels = data[9] & 0xFF;
        if (channels == 0) {
            throw corrupt("Opus identification header has zero channels");
        }
        int preSkip = le16(data, 10);
        int gain = (short) le16(data, 16);
        int family = data[18] & 0xFF;
        if (family == 0) {
            if (channels > 2) {
                throw new UnsupportedAudioFormatException("open",
                        "Opus channel mapping family 0 only supports mono and stereo", null, null);
            }
            opusHead = new OpusHead(channels, preSkip, gain, family, 1, channels - 1, null);
            return;
        }
        if (family != 1) {
            throw new UnsupportedAudioFormatException("open",
                    "unsupported Ogg Opus channel mapping family " + family, null, null);
        }
        if (channels > 8) {
            throw new UnsupportedAudioFormatException("open",
                    "Ogg Opus mapping family 1 supports at most 8 channels", null, null);
        }
        int required = 21 + channels;
        if (data.length < required) {
            throw corrupt("truncated Opus channel mapping table");
        }
        int streams = data[19] & 0xFF;
        int coupled = data[20] & 0xFF;
        if (streams == 0 || coupled > streams || streams + coupled > 255) {
            throw corrupt("invalid Opus stream/coupled-stream counts");
        }
        short[] mapping = new short[channels];
        for (int i = 0; i < channels; i++) {
            int index = data[21 + i] & 0xFF;
            if (index != 255 && index >= streams + coupled) {
                throw corrupt("Opus channel mapping references a missing coded channel");
            }
            mapping[i] = (short) index;
        }
        if (channels > 2) {
            throw new UnsupportedAudioFormatException("open",
                    "Ogg Opus stream has " + channels + " channels; Judio supports mono and stereo",
                    null, null);
        }
        opusHead = new OpusHead(channels, preSkip, gain, family, streams, coupled, mapping);
    }

    private void submitVorbisHeader(OggPacket packet) {
        Packet jorbisPacket = asJorbisPacket(packet, packet.granulePosition(), false);
        int result;
        try {
            result = vorbisInfo.synthesis_headerin(vorbisComment, jorbisPacket);
        } catch (RuntimeException e) {
            throw new CorruptFileException("open", "invalid Vorbis header packet (" + source + ")", null, e);
        }
        if (result != 0) {
            throw corrupt("invalid Vorbis header packet");
        }
    }

    void close() {
        if (!closed) {
            closed = true;
            headerPages.clear();
            if (vorbisInfo != null) {
                vorbisInfo.clear();
                vorbisInfo = null;
            }
            vorbisComment = null;
            opusHead = null;
            codecInfo = null;
            metadata = TrackMetadata.EMPTY;
        }
    }

    static Packet asJorbisPacket(OggPacket packet, long granulePosition, boolean endOfStream) {
        Packet jorbis = new Packet();
        jorbis.packet_base = packet.data();
        jorbis.packet = 0;
        jorbis.bytes = packet.data().length;
        jorbis.b_o_s = packet.beginningOfStream() ? 1 : 0;
        jorbis.e_o_s = endOfStream ? 1 : 0;
        jorbis.granulepos = granulePosition;
        jorbis.packetno = packet.packetNumber();
        return jorbis;
    }

    private CorruptFileException corrupt(String message) {
        return new CorruptFileException("open", message + " (" + source + ")", null, null);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static int le16(byte[] data, int offset) {
        return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
    }

    private record OpusHead(int channels, int preSkip, int outputGain, int mappingFamily,
                            int streamCount, int coupledStreamCount, short[] channelMapping) {
        private OpusHead {
            channelMapping = channelMapping == null ? null : channelMapping.clone();
        }

        @Override
        public short[] channelMapping() {
            return channelMapping == null ? null : channelMapping.clone();
        }
    }
}
