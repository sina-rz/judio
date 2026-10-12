package io.github.sinaraz.internal.processing;

/**
 * Playback-speed processor based on linear-interpolation resampling.
 *
 * <h2>Pitch behaviour</h2>
 * <b>This processor changes pitch.</b> Reading the input faster (speed &gt; 1) raises pitch
 * and reading slower lowers it, exactly like a tape or a vinyl record. For pitch-preserving
 * speed changes use {@link Wsola} instead.
 *
 * <h2>Quality and latency</h2>
 * Linear interpolation introduces a small amount of high-frequency attenuation and aliasing
 * at high speed factors. Latency is one input frame.
 *
 * <h2>Buffer continuity</h2>
 * The processor keeps the last input frame and the fractional read position between calls,
 * so processing a stream in arbitrary chunks gives the same output as processing it in one
 * call (apart from one trailing frame that is held back until the next call). Changing the
 * speed takes effect on the next call without a discontinuity in the read position.
 *
 * <p>Samples are interleaved stereo {@code float} frames. Not thread-safe.
 */
public final class SpeedProcessor {

    private double speed = 1.0;
    private double readPos;      // position relative to the start of the next input block
    private float tailLeft;      // input frame at index -1
    private float tailRight;

    public void setSpeed(double newSpeed) {
        if (Double.isNaN(newSpeed) || newSpeed <= 0.0 || Double.isInfinite(newSpeed)) {
            throw new IllegalArgumentException("speed must be a positive finite number: " + newSpeed);
        }
        this.speed = newSpeed;
    }

    public double speed() {
        return speed;
    }

    /** Upper bound on output frames produced by {@link #process} for {@code inFrames} input frames. */
    public int maxOutputFrames(int inFrames) {
        return (int) Math.ceil(inFrames / speed) + 2;
    }

    /**
     * Resamples {@code inFrames} stereo frames from {@code in} into {@code out}.
     *
     * @param out must hold at least {@code 2 * maxOutputFrames(inFrames)} floats
     * @return number of stereo frames written to {@code out}
     */
    public int process(float[] in, int inFrames, float[] out) {
        if (inFrames <= 0) {
            return 0;
        }
        if (speed == 1.0 && readPos == Math.floor(readPos)) {
            return copyAtUnitSpeed(in, inFrames, out);
        }
        int written = 0;
        // Invariant: readPos is in [-1, inFrames - 1) at the top of each iteration.
        while (readPos < inFrames - 1) {
            int i = (int) Math.floor(readPos);
            double frac = readPos - i;
            float l0 = sampleLeft(in, i);
            float l1 = sampleLeft(in, i + 1);
            float r0 = sampleRight(in, i);
            float r1 = sampleRight(in, i + 1);
            out[2 * written] = (float) (l0 + (l1 - l0) * frac);
            out[2 * written + 1] = (float) (r0 + (r1 - r0) * frac);
            written++;
            readPos += speed;
        }
        int last = 2 * (inFrames - 1);
        tailLeft = in[last];
        tailRight = in[last + 1];
        readPos -= inFrames;
        return written;
    }

    /**
     * Speed 1.0 from an integral read position: every output frame is an input frame, so the block
     * is a plain copy. Produces the same samples as the interpolating loop, which at fraction 0
     * returns the input frame unchanged.
     */
    private int copyAtUnitSpeed(float[] in, int inFrames, float[] out) {
        int first = (int) readPos;             // -1 means "the held-back frame from the last block"
        int offset = 0;
        if (first < 0) {
            out[0] = tailLeft;
            out[1] = tailRight;
            offset = 1;
            first = 0;
        }
        int copied = Math.max(0, inFrames - 1 - first);
        System.arraycopy(in, 2 * first, out, 2 * offset, 2 * copied);
        int written = offset + copied;
        int last = 2 * (inFrames - 1);
        tailLeft = in[last];
        tailRight = in[last + 1];
        // The interpolating loop stops at the first integral position >= inFrames - 1.
        readPos = (inFrames - 1) - inFrames;
        return written;
    }

    /**
     * Emits the frame held back at the end of the stream, so that the total output length equals
     * the total input length (for speeds of at least 1). The processor is reset afterwards.
     *
     * @param out must hold at least 2 stereo frames (4 floats)
     * @return number of stereo frames written (0, 1 or 2)
     */
    public int finish(float[] out) {
        int written = 0;
        double pos = readPos;
        while (pos < 0.0 && written < 2) {
            out[2 * written] = tailLeft;
            out[2 * written + 1] = tailRight;
            written++;
            pos += speed;
        }
        reset();
        return written;
    }

    /** Clears history so that the next input starts a new stream (used after seeks). */
    public void reset() {
        readPos = 0.0;
        tailLeft = 0.0f;
        tailRight = 0.0f;
    }

    private float sampleLeft(float[] in, int index) {
        return index < 0 ? tailLeft : in[2 * index];
    }

    private float sampleRight(float[] in, int index) {
        return index < 0 ? tailRight : in[2 * index + 1];
    }
}
