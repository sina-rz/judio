package io.github.sinaraz.internal.ogg;

import io.github.jaredmdobson.concentus.OpusDecoder;
import io.github.jaredmdobson.concentus.OpusException;
import io.github.jaredmdobson.concentus.OpusMSDecoder;

import io.github.sinaraz.error.CorruptFileException;

/** Concentus adapter for one Ogg Opus logical stream (up to stereo output). */
final class OpusPacketDecoder implements OggPacketDecoder {

    private static final int SAMPLE_RATE = 48_000;
    private static final int MAX_PACKET_FRAMES = 5_760;

    private final int channels;
    private final OpusDecoder singleStream;
    private final OpusMSDecoder multistream;

    OpusPacketDecoder(OggCodecInfo info) {
        this.channels = info.channels();
        try {
            if (info.mappingFamily() == 0) {
                this.singleStream = new OpusDecoder(SAMPLE_RATE, channels);
                this.multistream = null;
                this.singleStream.setGain(info.outputGain());
            } else {
                this.singleStream = null;
                this.multistream = OpusMSDecoder.create(channels, SAMPLE_RATE,
                        info.streamCount(), info.coupledStreamCount(), info.channelMapping());
                this.multistream.setGain(info.outputGain());
            }
        } catch (OpusException | RuntimeException e) {
            throw new CorruptFileException("open", "cannot initialize the pure-Java Opus decoder", null, e);
        }
    }

    @Override
    public byte[] decode(OggPacket packet) {
        byte[] data = packet.data();
        int expectedFrames = OggPacketDuration.opusSamples(data);
        short[] pcm = new short[MAX_PACKET_FRAMES * channels];
        int frames;
        try {
            if (singleStream != null) {
                frames = singleStream.decode(data, 0, data.length, pcm, 0, MAX_PACKET_FRAMES, false);
            } else {
                frames = multistream.decodeMultistream(data, 0, data.length, pcm, 0, MAX_PACKET_FRAMES, 0);
            }
        } catch (OpusException | RuntimeException e) {
            throw new CorruptFileException("read", "invalid Ogg Opus audio packet", null, e);
        }
        if (frames != expectedFrames || frames <= 0 || frames > MAX_PACKET_FRAMES) {
            throw new CorruptFileException("read",
                    "Opus decoder returned " + frames + " samples for a " + expectedFrames + "-sample packet",
                    null, null);
        }
        int sampleCount = Math.multiplyExact(frames, channels);
        byte[] out = new byte[Math.multiplyExact(sampleCount, 2)];
        for (int i = 0, offset = 0; i < sampleCount; i++) {
            short sample = pcm[i];
            out[offset++] = (byte) sample;
            out[offset++] = (byte) (sample >>> 8);
        }
        return out;
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public void close() {
        if (singleStream != null) {
            singleStream.resetState();
        }
        if (multistream != null) {
            multistream.ResetState();
        }
    }
}
