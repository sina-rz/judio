package io.github.sinaraz.support;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.time.Duration;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.error.DecoderException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.model.AudioInfo;

/**
 * In-memory decoder producing a constant 16-bit stereo or mono signal. Lets engine tests control
 * seekability, length, and failure points without touching the file system.
 */
public final class SyntheticDecoder implements AudioDecoder {

    /** Sample value written to every channel of every frame. */
    public static final short LEVEL = 8000;

    private final AudioInfo info;
    private final long totalFrames;
    private final int sampleRate;
    private final boolean seekable;
    private final long failAtFrame;
    private long position;

    /**
     * @param failAtFrame frame index at which {@link #read} starts throwing, or {@code -1} for never
     */
    public SyntheticDecoder(int sampleRate, int channels, double seconds, boolean seekable, long failAtFrame) {
        this.sampleRate = sampleRate;
        this.totalFrames = Math.round(seconds * sampleRate);
        this.seekable = seekable;
        this.failAtFrame = failAtFrame;
        int frameSize = channels * 2;
        this.info = new AudioInfo(
                Duration.ofNanos(Math.round(totalFrames * 1_000_000_000.0 / sampleRate)),
                sampleRate, channels, 16, frameSize, totalFrames,
                "PCM_SIGNED", ByteOrder.LITTLE_ENDIAN, seekable,
                "synthetic", "Synthetic");
    }

    public SyntheticDecoder(int sampleRate, int channels, double seconds) {
        this(sampleRate, channels, seconds, true, -1);
    }

    @Override
    public AudioInfo audioInfo() {
        return info;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (failAtFrame >= 0 && position >= failAtFrame) {
            throw new DecoderException("read", "synthetic failure", Path.of("synthetic"), null);
        }
        if (position >= totalFrames) {
            return -1;
        }
        int frameSize = info.frameSize();
        int frames = (int) Math.min(length / frameSize, totalFrames - position);
        if (frames <= 0) {
            throw new IOException("buffer smaller than one frame");
        }
        int p = offset;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < info.channels(); c++) {
                buffer[p++] = (byte) (LEVEL & 0xFF);
                buffer[p++] = (byte) ((LEVEL >> 8) & 0xFF);
            }
        }
        position += frames;
        return frames * frameSize;
    }

    @Override
    public void seek(Duration target) throws IOException {
        if (!seekable) {
            throw new SeekNotSupportedException("seek", "synthetic source is not seekable", null);
        }
        long frame = Math.round(target.toNanos() * (double) sampleRate / 1_000_000_000.0);
        position = Math.max(0, Math.min(totalFrames, frame));
    }

    @Override
    public boolean isSeekable() {
        return seekable;
    }

    @Override
    public long framePosition() {
        return position;
    }

    @Override
    public void close() {
        // nothing to release
    }
}
