package io.github.sinaraz.internal.ogg;

import java.io.ByteArrayOutputStream;

import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import io.github.sinaraz.error.CorruptFileException;

/** JOrbis packet decoder that emits PCM but leaves Ogg granule trimming to the shared adapter. */
final class VorbisPacketDecoder implements OggPacketDecoder {

    private final Info info;
    private final int channels;
    private final DspState dsp;
    private final Block block;
    private boolean closed;

    VorbisPacketDecoder(OggHeaderParser headers) {
        this.info = headers.vorbisInfo();
        this.channels = headers.info().channels();
        this.dsp = new DspState();
        if (dsp.synthesis_init(info) != 0) {
            throw new CorruptFileException("open", "cannot initialize the pure-Java Vorbis decoder", null, null);
        }
        this.block = new Block(dsp);
        this.block.init(dsp);
    }

    @Override
    public byte[] decode(OggPacket packet) {
        if (closed) {
            throw new IllegalStateException("Vorbis decoder is closed");
        }
        // Granule positions and EOS are deliberately handled by OggStreamDecoder, not JOrbis. This
        // keeps initial-origin and end-trim behavior consistent for local and incremental streams.
        com.jcraft.jogg.Packet jorbisPacket = OggHeaderParser.asJorbisPacket(packet, -1L, false);
        try {
            if (block.synthesis(jorbisPacket) != 0) {
                throw new CorruptFileException("read", "invalid Vorbis audio packet", null, null);
            }
            if (dsp.synthesis_blockin(block) != 0) {
                throw new CorruptFileException("read", "Vorbis synthesis rejected an audio packet", null, null);
            }
            return drainPcm();
        } catch (CorruptFileException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new CorruptFileException("read", "invalid Vorbis audio packet", null, e);
        }
    }

    private byte[] drainPcm() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        float[][][] pcm = new float[1][][];
        int[] index = new int[channels];
        int samples;
        while ((samples = dsp.synthesis_pcmout(pcm, index)) > 0) {
            for (int frame = 0; frame < samples; frame++) {
                for (int channel = 0; channel < channels; channel++) {
                    float value = pcm[0][channel][index[channel] + frame];
                    if (!Float.isFinite(value)) {
                        throw new CorruptFileException("read", "Vorbis decoder produced a non-finite PCM sample",
                                null, null);
                    }
                    int scaled = Math.round(value * 32767.0f);
                    int clamped = Math.clamp(scaled, -32768, 32767);
                    out.write(clamped & 0xFF);
                    out.write((clamped >>> 8) & 0xFF);
                }
            }
            if (dsp.synthesis_read(samples) != 0) {
                throw new CorruptFileException("read", "Vorbis decoder PCM buffer accounting failed", null, null);
            }
        }
        return out.toByteArray();
    }

    @Override
    public int channels() {
        return channels;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            block.clear();
            dsp.clear();
        }
    }
}
