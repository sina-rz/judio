package io.github.sinaraz.internal.processing;

/**
 * Chains the PCM processors used on the render path:
 *
 * <pre>
 *  canonical PCM16 (mono|stereo)
 *    └─▶ PcmCodec.toFloat ─▶ ChannelMixer.toStereo ─▶ tempo stage ─▶ VolumeProcessor
 *          ─▶ PanProcessor ─▶ PcmCodec.fromFloat (PCM16 stereo, saturating)
 * </pre>
 *
 * <p>The tempo stage is either {@link SpeedProcessor} (resampling: speed also changes pitch) or
 * {@link Wsola} (time-scale modification: speed changes tempo but keeps pitch). The caller picks one
 * per block. Switching between them resets the stage that becomes active, which costs one short gap.
 *
 * <p>The output is always stereo, so panning is meaningful even for mono sources. Each stage
 * is also available on its own and is unit-tested separately. Not thread-safe: the audio
 * worker thread owns the pipeline.
 */
public final class PlaybackPipeline {

    private final SpeedProcessor speed = new SpeedProcessor();
    private Wsola wsola;
    private int wsolaRate;
    private boolean wsolaActive;
    private float[] samples = new float[0];
    private float[] stereo = new float[0];
    private float[] shaped = new float[0];
    private byte[] output = new byte[0];

    /**
     * Prepares the pipeline for a stream at {@code sampleRate} and drops any history. Call before the
     * first block of each stream.
     *
     * @param sampleRate the stream's sample rate in hertz
     */
    public void prepare(int sampleRate) {
        if (wsola == null || wsolaRate != sampleRate) {
            wsola = new Wsola(sampleRate);
            wsolaRate = sampleRate;
        }
        reset();
    }

    /**
     * Processes one chunk of canonical PCM16 little-endian audio. The stereo PCM16 result is written
     * to the pipeline's internal buffer, which is reused, so read it with {@link #output()} before the
     * next call.
     *
     * @param pcm            input bytes
     * @param length         number of valid bytes in {@code pcm}; trailing partial frames are ignored
     * @param channels       channels in {@code pcm}: 1 or 2
     * @param speedFactor    playback speed; 1.0 is normal
     * @param preservePitch  {@code true} for WSOLA (pitch kept), {@code false} for resampling
     * @param gain           linear gain applied after the tempo stage
     * @param pan            stereo pan in [-1, 1]
     * @return number of valid output bytes; zero when the input held no whole frame
     * @throws IllegalStateException if {@code preservePitch} is set and {@link #prepare} has not been called
     */
    public int process(byte[] pcm, int length, int channels, double speedFactor, boolean preservePitch,
                       float gain, float pan) {
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("unsupported channel count: " + channels);
        }
        int frameBytes = 2 * channels;
        int frames = length / frameBytes;
        if (frames == 0) {
            return 0;
        }
        if (preservePitch && wsola == null) {
            throw new IllegalStateException("prepare(sampleRate) must be called before pitch-preserving playback");
        }
        int sampleCount = frames * channels;
        samples = ensure(samples, sampleCount);
        stereo = ensure(stereo, frames * 2);
        PcmCodec.toFloat16LE(pcm, 0, frames * frameBytes, samples, 0);
        ChannelMixer.toStereo(samples, frames, channels, stereo);

        int outFrames;
        if (preservePitch) {
            selectWsola();
            outFrames = wsola.process(stereo, frames, speedFactor);
            shaped = wsola.output();
        } else {
            selectResampler();
            speed.setSpeed(speedFactor);
            shaped = ensure(shaped, speed.maxOutputFrames(frames) * 2);
            outFrames = speed.process(stereo, frames, shaped);
        }

        VolumeProcessor.apply(shaped, outFrames * 2, gain);
        if (pan != 0.0f) {
            PanProcessor.apply(shaped, outFrames, pan);
        }
        output = ensureBytes(output, outFrames * 4);
        PcmCodec.fromFloat16LE(shaped, outFrames * 2, output, 0);
        return outFrames * 4;
    }

    /**
     * Flushes the audio held back at the end of a stream, after volume and pan have been applied,
     * into the internal output buffer.
     *
     * @return number of valid output bytes; zero when nothing was held back
     */
    public int finish(float gain, float pan) {
        int frames;
        if (wsolaActive) {
            frames = wsola.finish();
            shaped = wsola.output();
        } else {
            shaped = ensure(shaped, 2 * 2);
            frames = speed.finish(shaped);
        }
        VolumeProcessor.apply(shaped, frames * 2, gain);
        if (pan != 0.0f) {
            PanProcessor.apply(shaped, frames, pan);
        }
        output = ensureBytes(output, frames * 4);
        PcmCodec.fromFloat16LE(shaped, frames * 2, output, 0);
        return frames * 4;
    }

    /**
     * Source frames the tempo stage has received but not yet output. The engine adds this to its
     * position estimate so the displayed time matches the audio being heard.
     *
     * @return look-ahead in source frames; zero for resampling
     */
    public long latencyFrames() {
        return wsolaActive ? wsola.latencyFrames() : 0L;
    }

    /**
     * The stereo PCM16 bytes written by the last {@link #process} or {@link #finish} call. The array is
     * reused by later calls, so copy it if it must outlive them. Only the first returned count is valid.
     */
    public byte[] output() {
        return output;
    }

    /** Drops tempo-stage history. Call before reading from a new position. */
    public void reset() {
        speed.reset();
        if (wsola != null) {
            wsola.reset();
        }
        wsolaActive = false;
    }

    private void selectWsola() {
        if (!wsolaActive) {
            speed.reset();
            wsola.reset();
            wsolaActive = true;
        }
    }

    private void selectResampler() {
        if (wsolaActive) {
            wsola.reset();
            speed.reset();
            wsolaActive = false;
        }
    }

    private static float[] ensure(float[] array, int needed) {
        return array.length >= needed ? array : new float[needed];
    }

    private static byte[] ensureBytes(byte[] array, int needed) {
        return array.length >= needed ? array : new byte[needed];
    }
}
