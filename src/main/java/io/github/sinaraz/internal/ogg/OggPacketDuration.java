package io.github.sinaraz.internal.ogg;

import com.jcraft.jogg.Packet;
import com.jcraft.jorbis.Info;
import io.github.jaredmdobson.concentus.OpusPacketInfo;

import io.github.sinaraz.error.CorruptFileException;

/** Packet-level duration checks used both by the local index scan and the incremental decoder. */
final class OggPacketDuration {

    private OggPacketDuration() {}

    static int opusSamples(byte[] packet) {
        if (packet.length == 0 || packet.length > 61_440) {
            throw corrupt("invalid Ogg Opus packet size");
        }
        try {
            int frames = OpusPacketInfo.getNumSamples(packet, 0, packet.length, 48_000);
            if (frames <= 0 || frames > 5_760) {
                throw corrupt("invalid Ogg Opus packet duration");
            }
            return frames;
        } catch (RuntimeException e) {
            throw new CorruptFileException("read", "invalid Ogg Opus packet", null, e);
        }
    }

    static int vorbisBlockSize(Info info, OggPacket packet) {
        try {
            Packet jorbisPacket = OggHeaderParser.asJorbisPacket(packet, -1, false);
            int size = info.blocksize(jorbisPacket);
            if (size <= 0) {
                throw corrupt("invalid Vorbis audio packet");
            }
            return size;
        } catch (RuntimeException e) {
            throw new CorruptFileException("read", "invalid Vorbis audio packet", null, e);
        }
    }

    private static CorruptFileException corrupt(String message) {
        return new CorruptFileException("read", message, null, null);
    }
}
