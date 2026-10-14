package io.github.sinaraz.internal.decoder;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Objects;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.error.DecoderException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.AudioInfo;

/**
 * Decoder for WAV, AIFF or AU data arriving as a stream, using the JDK's Java Sound converters. The
 * output is canonical signed 16-bit little-endian PCM at the stream's sample rate.
 *
 * <p>The duration is reported only when the header states a frame count. A WAV stream can be sought
 * when the server supports byte ranges and the file's {@code data} chunk is in the first 64 KB. A seek
 * reopens the stream at the byte offset of the target frame and rebuilds the conversion. AIFF and AU
 * streams cannot be sought.
 */
public final class StreamingPcmDecoder implements AudioDecoder {

    private static final String NAME = "JavaSound (stream)";
    /** How far into the file the WAV header may be; the header is read with mark and reset. */
    private static final int HEADER_LIMIT = 64 * 1024;

    private final String source;
    private final AudioFormat sourceFormat;
    private final AudioFormat targetFormat;
    private final RangeOpener reopen;
    private final long dataStart;      // byte offset of the first PCM frame, or -1 if not seekable
    private final long totalFrames;
    private final int frameSize;
    private final int sourceFrameSize;  // frame size in the source encoding (differs from target for 8/24-bit)
    private final AudioInfo info;
    private AudioInputStream stream;
    private long framePos;
    private boolean closed;

    /**
     * Opens the container and prepares the conversion. The stream cannot be repositioned.
     *
     * @param in     the raw bytes, starting at the container header; the decoder closes it
     * @param source a name for error messages
     * @throws IOException if the data is not a supported WAV, AIFF or AU stream
     */
    public StreamingPcmDecoder(InputStream in, String source) throws IOException {
        this(in, source, -1L, null);
    }

    /**
     * Opens the container and prepares the conversion.
     *
     * @param in         the raw bytes, starting at the container header; the decoder closes it
     * @param source     a name for error messages
     * @param byteLength the size of the whole file in bytes, or {@code -1} if unknown
     * @param reopen     opens the same bytes from an offset, used for seeking; {@code null} if the
     *                   stream cannot be repositioned
     * @throws IOException if the data is not a supported WAV, AIFF or AU stream
     */
    public StreamingPcmDecoder(InputStream in, String source, long byteLength, RangeOpener reopen)
            throws IOException {
        this.source = Objects.requireNonNull(source, "source");
        this.reopen = reopen;
        InputStream buffered = in.markSupported() ? in : new BufferedInputStream(in, HEADER_LIMIT);
        long[] data = findWaveData(buffered);
        AudioInputStream probe;
        try {
            probe = AudioSystem.getAudioInputStream(new BufferedInputStream(new FullReadInputStream(buffered)));
        } catch (UnsupportedAudioFileException e) {
            buffered.close();
            throw new UnsupportedAudioFormatException("open", "not a supported WAV/AIFF/AU stream: " + source, null, e);
        }
        try {
            AudioFormat format = probe.getFormat();
            int channels = format.getChannels();
            if (channels < 1 || channels > 2) {
                throw new UnsupportedAudioFormatException("open",
                        "only mono and stereo are supported, stream has " + channels + " channels", null, null);
            }
            float rate = format.getSampleRate();
            if (rate <= 0) {
                throw new UnsupportedAudioFormatException("open", "unknown sample rate in " + source, null, null);
            }
            AudioFormat target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                    channels * 2, rate, false);
            if (!AudioSystem.isConversionSupported(target, format)) {
                throw new UnsupportedAudioFormatException("open",
                        "no PCM conversion from " + format + " to canonical PCM16", null, null);
            }
            this.sourceFormat = format;
            this.targetFormat = target;
            this.stream = AudioSystem.getAudioInputStream(target, probe);
            this.frameSize = target.getFrameSize();
            this.sourceFrameSize = format.getFrameSize();
            long frames = probe.getFrameLength();
            this.totalFrames = Math.max(0L, frames);
            Duration duration = frames > 0
                    ? Duration.ofNanos(Math.round(frames * 1_000_000_000.0 / rate))
                    : Duration.ZERO;
            boolean pcm = format.getEncoding() == AudioFormat.Encoding.PCM_SIGNED
                    || format.getEncoding() == AudioFormat.Encoding.PCM_UNSIGNED;
            this.dataStart = (data != null && pcm && reopen != null && byteLength > 0
                    && format.getFrameSize() > 0 && frames > 0) ? data[0] : -1L;
            if (sourceFrameSize <= 0 && dataStart >= 0) {
                throw new UnsupportedAudioFormatException("open",
                        "unknown source frame size in " + source, null, null);
            }
            this.info = new AudioInfo(
                    duration,
                    (int) rate,
                    channels,
                    16,
                    frameSize,
                    totalFrames,
                    "PCM_SIGNED",
                    ByteOrder.LITTLE_ENDIAN,
                    dataStart >= 0,
                    format.getEncoding() + " " + format.getSampleSizeInBits() + "-bit (stream)",
                    NAME);
        } catch (RuntimeException e) {
            probe.close();
            throw e;
        }
    }

    @Override
    public AudioInfo audioInfo() {
        return info;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        ensureOpen();
        if (length < frameSize) {
            throw new IllegalArgumentException("length must hold at least one frame: " + length);
        }
        int want = length - (length % frameSize);
        int n = stream.read(buffer, offset, want);
        if (n < 0) {
            return -1;
        }
        int rem = n % frameSize;
        int zeroReads = 0;
        while (rem != 0) {
            int k = stream.read(buffer, offset + n, frameSize - rem);
            if (k < 0) {
                n -= rem; // drop a trailing partial frame at the end of the stream
                break;
            }
            if (k == 0 && ++zeroReads > 1000) {
                throw new IOException("audio stream returned no data repeatedly: " + source);
            }
            n += k;
            rem = n % frameSize;
        }
        if (n == 0) {
            return -1;
        }
        framePos += n / frameSize;
        return n;
    }

    /**
     * Repositions the stream by reconnecting at the byte offset of the target frame.
     *
     * @throws SeekNotSupportedException if this stream cannot be repositioned
     * @throws IOException if the server cannot provide the new position
     */
    @Override
    public void seek(Duration position) throws IOException {
        ensureOpen();
        if (!isSeekable()) {
            throw new SeekNotSupportedException("seek",
                    "this stream cannot be repositioned (the format, server, or file does not allow it)", null);
        }
        Objects.requireNonNull(position, "position");
        long target = Math.round(Math.max(0L, position.toNanos()) / 1e9 * targetFormat.getSampleRate());
        target = Math.min(target, totalFrames);
        // The byte offset must use the SOURCE frame size: for 8-bit or mu-law
        // sources one source frame is smaller than one canonical 16-bit frame.
        InputStream raw = target >= totalFrames
                ? new ByteArrayInputStream(new byte[0])
                : reopen.open(dataStart + target * sourceFrameSize);
        AudioInputStream source = new AudioInputStream(
                new BufferedInputStream(new FullReadInputStream(raw)), sourceFormat, totalFrames - target);
        AudioInputStream replacement = AudioSystem.getAudioInputStream(targetFormat, source);
        // Swap only once the replacement is fully built, so a failed seek
        // leaves the current stream usable.
        AudioInputStream old = this.stream;
        this.stream = replacement;
        this.framePos = target;
        old.close();
    }

    @Override
    public boolean isSeekable() {
        return info.seekable();
    }

    @Override
    public long framePosition() {
        return framePos;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            stream.close();
        }
    }

    /**
     * Finds the {@code data} chunk of a RIFF/WAVE header, returning {@code {byteOffset, byteSize}}, or
     * {@code null} if the stream is not WAVE or the chunk is not within {@link #HEADER_LIMIT}. The
     * stream position is restored.
     */
    private static long[] findWaveData(InputStream in) throws IOException {
        if (!in.markSupported()) {
            return null;
        }
        in.mark(HEADER_LIMIT);
        try {
            byte[] riff = in.readNBytes(12);
            if (riff.length < 12 || !ascii(riff, 0).equals("RIFF") || !ascii(riff, 8).equals("WAVE")) {
                return null;
            }
            long pos = 12;
            while (pos + 8 <= HEADER_LIMIT) {
                byte[] chunk = in.readNBytes(8);
                if (chunk.length < 8) {
                    return null;
                }
                long size = (chunk[4] & 0xFFL) | ((chunk[5] & 0xFFL) << 8)
                        | ((chunk[6] & 0xFFL) << 16) | ((chunk[7] & 0xFFL) << 24);
                pos += 8;
                if (ascii(chunk, 0).equals("data")) {
                    return new long[] {pos, size};
                }
                long skip = size + (size & 1);
                if (pos + skip + 8 > HEADER_LIMIT) {
                    return null;
                }
                if (in.readNBytes((int) skip).length < skip) {
                    return null;
                }
                pos += skip;
            }
            return null;
        } finally {
            in.reset();
        }
    }

    private static String ascii(byte[] bytes, int at) {
        return new String(bytes, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
    }

    private void ensureOpen() {
        if (closed) {
            throw new DecoderException("read", "decoder is closed", null, null);
        }
    }
}
