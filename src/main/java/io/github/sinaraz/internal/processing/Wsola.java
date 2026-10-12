package io.github.sinaraz.internal.processing;

import java.util.Arrays;

/**
 * Waveform-Similarity-based Overlap-Add (WSOLA) time-scale modification.
 *
 * <p>Changes the duration of stereo audio without changing its pitch. A playback speed of 2.0
 * halves the duration and keeps the pitch; 0.5 doubles the duration. This is the
 * pitch-preserving counterpart of {@link SpeedProcessor}, which resamples and therefore changes
 * pitch.
 *
 * <h2>Algorithm</h2>
 * Output is built from Hann-windowed segments of the input, placed every {@code hop} output frames.
 * The nominal read position of segment {@code k} is {@code k * hop * speed}. Around that position the
 * algorithm searches for the start whose waveform best continues the previous segment's overlap
 * region, by normalised cross-correlation. Choosing the best-matching start avoids the phase jumps
 * that make plain overlap-add sound like a warbling echo. The search has two stages:
 * <ol>
 *   <li>a coarse search over {@code ±search} frames, correlating every {@code stride}-th frame of
 *       the mono mix, which keeps the cost manageable;</li>
 *   <li>a refinement at full resolution within {@code ±stride} of the coarse winner.</li>
 * </ol>
 *
 * <h2>Latency, length, and streaming</h2>
 * The processor needs about {@code frame + search} frames of look-ahead, so it holds back that much
 * audio (roughly 35 ms at 44.1 kHz). {@link #process} returns only the frames that are final.
 * {@link #finish} flushes the rest and pads with silence. The total output length equals
 * {@code round(total input / speed)}, so a whole file keeps its expected duration. The result does
 * not depend on how the input is split into chunks.
 *
 * <p>Samples are interleaved stereo {@code float}s. Not thread-safe; the audio worker owns it.
 */
public final class Wsola {

    /** Segment length in seconds. */
    private static final double FRAME_SECONDS = 0.025;
    /** Maximum deviation from the nominal read position in seconds. */
    private static final double SEARCH_SECONDS = 0.010;
    /** Compaction threshold: the input buffer is shifted once this many frames can be dropped. */
    private static final int COMPACT_THRESHOLD = 8192;

    private final int frame;     // N: segment length in frames (even)
    private final int hop;       // N / 2: synthesis hop; overlap is also N / 2
    private final int search;    // coarse search half-width in frames
    private final int stride;    // coarse correlation decimation
    private final float[] window;

    private float[] in = new float[0];   // stereo input buffer; in[0] is absolute frame inBase
    private int inFrames;                // valid frames in the input buffer
    private long inBase;                 // absolute input index of the first buffered frame

    private float[] acc = new float[0];  // stereo overlap-add accumulator; acc[0] is absolute frame accBase
    private int accFrames;               // valid frames in the accumulator
    private long accBase;                // absolute output index of the first accumulator frame

    private float[] out = new float[0];  // stereo final output of the most recent call
    private int outFrames;

    private float[] template = new float[0]; // mono overlap of the previous segment's continuation

    private long frameIndex;     // k: the next synthesis frame to produce
    private long prevStart;      // absolute input start of the previous segment
    private double nominal;      // nominal absolute input start of the previous segment
    private double idealOut;     // output frames expected for all input so far
    private double lastSpeed = 1.0;
    private long emitLimit = Long.MAX_VALUE; // caps output while flushing, so the total length is exact

    /**
     * Creates a processor for the given sample rate.
     *
     * @param sampleRate the rate of the audio that will be processed, in hertz
     */
    public Wsola(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        int n = (int) Math.round(FRAME_SECONDS * sampleRate);
        this.frame = Math.max(64, n + (n & 1));
        this.hop = frame / 2;
        this.search = Math.max(1, (int) Math.round(SEARCH_SECONDS * sampleRate));
        this.stride = Math.max(1, (int) Math.round(sampleRate / 11_025.0));
        this.window = new float[frame];
        for (int i = 0; i < frame; i++) {
            window[i] = (float) (0.5 - 0.5 * Math.cos(2.0 * Math.PI * i / frame));
        }
        this.template = new float[hop];
    }

    /** Segment length in frames. Exposed for tests and diagnostics. */
    public int frameLength() {
        return frame;
    }

    /** Maximum deviation from the nominal read position, in frames. Exposed for tests and diagnostics. */
    public int searchRange() {
        return search;
    }

    /**
     * Feeds {@code frames} stereo input frames and returns the final output produced by this call.
     *
     * @param src    interleaved stereo input, at least {@code 2 * frames} floats
     * @param frames number of input frames in {@code src}
     * @param speed  time scale: 2.0 is twice as fast, 0.5 half as fast; must be positive and finite
     * @return number of output frames available from {@link #output()}; may be zero
     */
    public int process(float[] src, int frames, double speed) {
        requireSpeed(speed);
        outFrames = 0;
        if (frames <= 0) {
            return 0;
        }
        lastSpeed = speed;
        compact(speed);
        append(src, frames);
        idealOut += frames / speed;
        while (canProduce(speed)) {
            produceFrame(speed);
        }
        return outFrames;
    }

    /**
     * Flushes the audio held back at the end of the stream. Pads with silence where the algorithm
     * needs look-ahead, and emits exactly {@code round(total input / speed)} frames in total since the
     * last {@link #reset}. The processor is reset afterward.
     *
     * @return number of output frames available from {@link #output()}
     */
    public int finish() {
        outFrames = 0;
        long target = Math.round(idealOut);
        appendSilence(frame + search + stride + hop);
        emitLimit = target;
        while (frameIndex * hop < target && canProduce(lastSpeed)) {
            produceFrame(lastSpeed);
        }
        emitUpTo(target);
        reset();
        return outFrames;
    }

    /**
     * The stereo output of the most recent {@link #process} or {@link #finish} call. The array is
     * reused by later calls, so copy it if it must survive them. Only the first returned count is valid.
     *
     * @return the interleaved stereo output buffer
     */
    public float[] output() {
        return out;
    }

    /**
     * Input frames that have been received but not yet reached the read position of the next
     * segment. The engine adds this to its position estimate so that the displayed time matches what
     * is being heard.
     *
     * @return look-ahead in input frames
     */
    public long latencyFrames() {
        return Math.max(0L, inBase + inFrames - Math.round(nominalNext(lastSpeed)));
    }

    /** Drops all history, so the next input starts a new stream (used after seeks and loads). */
    public void reset() {
        emitLimit = Long.MAX_VALUE;
        inFrames = 0;
        inBase = 0L;
        accFrames = 0;
        accBase = 0L;
        frameIndex = 0L;
        prevStart = 0L;
        nominal = 0.0;
        idealOut = 0.0;
        Arrays.fill(acc, 0.0f);
    }

    // ------------------------------------------------------------------ production

    private boolean canProduce(double speed) {
        if (frameIndex == 0) {
            return inBase + inFrames >= frame;
        }
        long c = Math.round(nominalNext(speed));
        return inBase + inFrames >= c + search + frame;
    }

    private double nominalNext(double speed) {
        return frameIndex == 0 ? 0.0 : nominal + hop * speed;
    }

    private void produceFrame(double speed) {
        double nn = nominalNext(speed);
        long start;
        if (frameIndex == 0) {
            start = 0L;
        } else {
            long c = Math.round(nn);
            long continuation = prevStart + hop;
            fillTemplate(continuation);
            start = bestStart(c);
        }
        nominal = nn;
        prevStart = start;
        overlapAdd(start, frameIndex * hop);
        frameIndex++;
        // Segment k+1 starts at (k+1)*hop, so everything before that is final now.
        emitUpTo(Math.min(frameIndex * hop, emitLimit));
    }

    /** Mono mix of the overlap region that the next segment should continue. */
    private void fillTemplate(long continuation) {
        int base = (int) (continuation - inBase);
        for (int i = 0; i < hop; i++) {
            int j = 2 * (base + i);
            template[i] = in[j] + in[j + 1];
        }
    }

    /** Two-stage search for the candidate start that best continues the template. */
    private long bestStart(long c) {
        long bestCoarse = c;
        double bestScore = Double.NEGATIVE_INFINITY;
        // Near the start of the stream the window can reach before the first buffered frame.
        for (long p = Math.max(inBase, c - search); p <= c + search; p++) {
            double score = score(p, stride);
            if (score > bestScore) {
                bestScore = score;
                bestCoarse = p;
            }
        }
        long best = bestCoarse;
        bestScore = Double.NEGATIVE_INFINITY;
        for (long p = bestCoarse - stride; p <= bestCoarse + stride; p++) {
            if (p < inBase) {
                continue;
            }
            double score = score(p, 1);
            if (score > bestScore) {
                bestScore = score;
                best = p;
            }
        }
        return best;
    }

    /** Normalised correlation of the candidate with the template, sampled every {@code step} frames. */
    private double score(long p, int step) {
        int base = (int) (p - inBase);
        double dot = 0.0;
        double energy = 0.0;
        for (int i = 0; i < hop; i += step) {
            int j = 2 * (base + i);
            double m = in[j] + in[j + 1];
            dot += m * template[i];
            energy += m * m;
        }
        return energy > 1e-12 ? dot / Math.sqrt(energy) : 0.0;
    }

    /** Adds the Hann-windowed segment starting at absolute input {@code start} to the accumulator. */
    private void overlapAdd(long start, long outPos) {
        int srcBase = (int) (start - inBase);
        int dstBase = (int) (outPos - accBase);
        ensureAcc(dstBase + frame);
        for (int i = 0; i < frame; i++) {
            float w = window[i];
            int s = 2 * (srcBase + i);
            int d = 2 * (dstBase + i);
            acc[d] += w * in[s];
            acc[d + 1] += w * in[s + 1];
        }
        accFrames = Math.max(accFrames, dstBase + frame);
    }

    /** Moves accumulator frames before absolute output position {@code boundary} into the output. */
    private void emitUpTo(long boundary) {
        int n = (int) (boundary - accBase);
        if (n <= 0) {
            return;
        }
        ensureAcc(n);
        ensureOut(outFrames + n);
        System.arraycopy(acc, 0, out, 2 * outFrames, 2 * n);
        outFrames += n;
        int old = accFrames;
        int remaining = Math.max(0, old - n);
        if (remaining > 0) {
            System.arraycopy(acc, 2 * n, acc, 0, 2 * remaining);
        }
        // Clear the vacated tail: later segments add into it, and it must start at zero.
        if (old > remaining) {
            Arrays.fill(acc, 2 * remaining, 2 * old, 0.0f);
        }
        accFrames = remaining;
        accBase += n;
    }

    // ------------------------------------------------------------------ buffers

    private void append(float[] src, int frames) {
        ensureIn(inFrames + frames);
        System.arraycopy(src, 0, in, 2 * inFrames, 2 * frames);
        inFrames += frames;
    }

    private void appendSilence(int frames) {
        ensureIn(inFrames + frames);
        Arrays.fill(in, 2 * inFrames, 2 * (inFrames + frames), 0.0f);
        inFrames += frames;
    }

    /** Drops input that no future segment can read, so the buffer does not grow without bound. */
    private void compact(double speed) {
        long keep = inBase;
        if (frameIndex > 0) {
            long nextNominal = Math.round(nominal + hop * speed);
            keep = Math.min(prevStart + hop, nextNominal - search - stride);
        }
        int drop = (int) Math.min(inFrames, Math.max(0L, keep - inBase));
        if (drop >= COMPACT_THRESHOLD) {
            System.arraycopy(in, 2 * drop, in, 0, 2 * (inFrames - drop));
            inFrames -= drop;
            inBase += drop;
        }
    }

    private void ensureIn(int frames) {
        if (in.length < 2 * frames) {
            in = Arrays.copyOf(in, Math.max(2 * frames, in.length * 2));
        }
    }

    private void ensureAcc(int frames) {
        if (acc.length < 2 * frames) {
            acc = Arrays.copyOf(acc, Math.max(2 * frames, acc.length * 2));
        }
    }

    private void ensureOut(int frames) {
        if (out.length < 2 * frames) {
            out = Arrays.copyOf(out, Math.max(2 * frames, out.length * 2));
        }
    }

    private static void requireSpeed(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed) || speed <= 0.0) {
            throw new IllegalArgumentException("speed must be a positive finite number: " + speed);
        }
    }
}
