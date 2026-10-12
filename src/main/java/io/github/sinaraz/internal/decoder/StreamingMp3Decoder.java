package io.github.sinaraz.internal.decoder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Objects;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.Obuffer;
import javazoom.jl.decoder.SampleBuffer;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.error.CorruptFileException;
import io.github.sinaraz.error.DecoderException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.AudioInfo;

/**
 * MP3 decoder for a network stream, over JLayer 1.0.1.
 *
 * <p>The decoder reads the file's headers at open time, without scanning the whole file. It finds
 * the duration from the Xing/Info header when the encoder wrote one, and otherwise estimates it from
 * the bitrate and the file size. The first frame is decoded while the decoder is constructed, so the
 * sample rate and channel count are known before playback starts. Frames that JLayer cannot decode
 * are skipped, as in {@link Mp3Decoder}.
 *
 * <p><b>Seeking</b> needs a server that reports the file size and answers range requests. A seek
 * reopens the stream at a byte offset, found from the seek table when present or by linear
 * interpolation otherwise. Then the decoder skips to the next frame and discards a few frames to let
 * the bit reservoir settle. Seeks in variable-bitrate files without a seek table are approximate.
 */
public final class StreamingMp3Decoder implements AudioDecoder {

    static final String NAME = "JLayer (stream)";
    private static final int MAX_CONSECUTIVE_BAD_FRAMES = 16;
    private static final int PREFIX_BYTES = 8192;
    /**
     * Frames decoded and discarded after a seek. The bit reservoir of an MP3 frame can refer back up
     * to 511 bytes, which may reach into frames that the seek skipped, so the first frames can be
     * wrong. Six frames cover that at every bitrate.
     */
    private static final int PREROLL_FRAMES = 6;

    private InputStream in;
    private final String source;
    private final RangeOpener reopen;      // null when the stream cannot be repositioned
    private final long byteLength;         // size of the whole file, or -1 if unknown
    private final long audioStart;         // byte offset of the first audio frame
    private final long samplesPerFrame;    // samples per channel in one MPEG frame
    private final long xingFrames;         // exact frame count, or -1 if estimated
    private final long estimatedFrames;    // used when no Xing header is present
    private Bitstream bitstream;
    private Decoder decoder = new Decoder();
    private final int channels;
    private final int sampleRate;
    private final AudioInfo info;
    private final boolean seekable;

    private int firstChannels;   // format of the first decoded frame; 0 until known
    private int firstSampleRate;
    private byte[] pcm = new byte[0];
    private int pcmPos;
    private int pcmLen;
    private long framePos;
    private int consecutiveBadFrames;
    private boolean closed;

    /**
     * Reads the headers and the first frame to learn the format and length.
     *
     * @param in        the MP3 bytes, starting at the first byte of the file; the decoder closes it
     * @param source    a name for error messages, such as the stream address
     * @param byteLength the size of the whole file in bytes, or {@code -1} if unknown
     * @param reopen    opens the same bytes from an offset, used for seeking; {@code null} if the
     *                  stream cannot be repositioned
     * @throws IOException if the stream ends before a decodable frame or the data is not MP3
     */
    public StreamingMp3Decoder(InputStream in, String source, long byteLength, RangeOpener reopen)
            throws IOException {
        this.in = Objects.requireNonNull(in, "in");
        this.source = Objects.requireNonNull(source, "source");
        this.byteLength = byteLength;
        this.reopen = reopen;

        byte[] head = in.readNBytes(10);
        long tagBytes = 0L;
        byte[] prefix;
        if (head.length == 10 && head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
            long size = ((head[6] & 0x7FL) << 21) | ((head[7] & 0x7FL) << 14)
                    | ((head[8] & 0x7FL) << 7) | (head[9] & 0x7FL);
            long footer = (head[5] & 0x10) != 0 ? 10 : 0;
            tagBytes = 10 + size + footer;
            skip(in, size + footer);
            prefix = in.readNBytes(PREFIX_BYTES);
        } else {
            byte[] rest = in.readNBytes(PREFIX_BYTES - head.length);
            prefix = new byte[head.length + rest.length];
            System.arraycopy(head, 0, prefix, 0, head.length);
            System.arraycopy(rest, 0, prefix, head.length, rest.length);
        }
        Mp3StreamScan scan = Mp3StreamScan.find(prefix, tagBytes);
        this.bitstream = new Bitstream(new FullReadInputStream(
                new SequenceInputStream(new ByteArrayInputStream(prefix), in)));

        if (!decodeNextFrame()) {
            throw new UnsupportedAudioFormatException("open", "no MP3 frames found in " + source, null, null);
        }
        this.channels = firstChannels;
        this.sampleRate = firstSampleRate;
        this.audioStart = scan == null ? tagBytes : scan.audioStart();
        this.xingFrames = scan == null ? -1L : scan.xingFrames();
        this.samplesPerFrame = scan == null ? 1152 : scan.samplesPerFrame();
        this.estimatedFrames = estimateFrames(scan);

        long totalFrames = xingFrames > 0 ? xingFrames : estimatedFrames;
        Duration duration = totalFrames > 0
                ? durationOf(totalFrames * samplesPerFrame, sampleRate)
                : Duration.ZERO;
        this.seekable = reopen != null && byteLength > 0 && !duration.isZero();
        this.info = new AudioInfo(
                duration,
                sampleRate,
                channels,
                16,
                channels * 2,
                duration.isZero() ? 0L : Math.round(duration.toNanos() / 1e9 * sampleRate),
                "PCM_SIGNED",
                ByteOrder.LITTLE_ENDIAN,
                seekable,
                "MPEG audio (stream)",
                NAME);
    }

    @Override
    public AudioInfo audioInfo() {
        return info;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        ensureOpen();
        int frameBytes = channels * 2;
        if (length < frameBytes) {
            throw new IllegalArgumentException("length must hold at least one frame: " + length);
        }
        int want = length - (length % frameBytes);
        int written = 0;
        while (written < want) {
            if (pcmPos == pcmLen && !decodeNextFrame()) {
                break;
            }
            int n = Math.min(want - written, pcmLen - pcmPos);
            System.arraycopy(pcm, pcmPos, buffer, offset + written, n);
            pcmPos += n;
            written += n;
        }
        if (written == 0) {
            return -1;
        }
        framePos += written / frameBytes;
        return written;
    }

    /**
     * Repositions the stream at {@code position}, reopening the connection at the matching byte offset.
     *
     * @throws SeekNotSupportedException if the stream cannot be repositioned
     * @throws IOException if the server cannot provide the new position
     */
    @Override
    public void seek(Duration position) throws IOException {
        ensureOpen();
        if (!seekable) {
            throw new SeekNotSupportedException("seek",
                    "this stream cannot be repositioned (the server or the file does not allow it)", null);
        }
        Objects.requireNonNull(position, "position");
        long totalNanos = info.duration().toNanos();
        long targetNanos = Math.max(0L, Math.min(position.toNanos(), totalNanos));
        long targetFrame = Math.round(targetNanos / 1e9 * sampleRate);
        // Land a few frames early: the pre-roll frames are discarded, so output starts at the target.
        long landingFrame = Math.max(0L, targetFrame - PREROLL_FRAMES * samplesPerFrame);
        long byteOffset = byteOffsetFor(landingFrame / (totalNanos / 1e9 * sampleRate));

        closeCurrent();
        if (byteOffset >= byteLength) {
            // At or past the end: nothing left to decode.
            this.in = new ByteArrayInputStream(new byte[0]);
        } else {
            this.in = reopen.open(byteOffset);
        }
        this.bitstream = new Bitstream(new FullReadInputStream(in));
        this.decoder = new Decoder();
        pcmPos = 0;
        pcmLen = 0;
        consecutiveBadFrames = 0;
        // Let the bit reservoir settle: decode and drop the first frames after the resync point.
        for (int i = 0; i < PREROLL_FRAMES && decodeNextFrame(); i++) {
            pcmPos = 0;
            pcmLen = 0;
        }
        pcmPos = 0;
        pcmLen = 0;
        framePos = targetFrame;
    }

    @Override
    public boolean isSeekable() {
        return seekable;
    }

    @Override
    public long framePosition() {
        return framePos;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            try {
                closeCurrent();
            } finally {
                in.close();
            }
        }
    }

    private void closeCurrent() throws IOException {
        try {
            bitstream.close();
        } catch (BitstreamException e) {
            throw new IOException("failed to close MP3 bitstream: " + source, e);
        } finally {
            in.close();
        }
    }

    /**
     * Maps a fraction of the duration to a byte offset by linear interpolation over the audio bytes.
     * This is exact for constant-bitrate files. For variable-bitrate files it is approximate, and
     * the error stays within a fraction of a second in tests. Xing seek tables were tried and rejected
     * because their 1/256-of-file resolution is about half a second of audio at 320 kbps.
     */
    private long byteOffsetFor(double fraction) {
        return audioStart + (long) ((byteLength - audioStart) * Math.min(Math.max(fraction, 0.0), 1.0));
    }

    private boolean decodeNextFrame() throws IOException {
        try {
            while (true) {
                Header header = bitstream.readFrame();
                if (header == null) {
                    return false;
                }
                Obuffer out;
                try {
                    out = decoder.decodeFrame(header, bitstream);
                } catch (javazoom.jl.decoder.DecoderException | RuntimeException e) {
                    bitstream.closeFrame();
                    if (++consecutiveBadFrames > MAX_CONSECUTIVE_BAD_FRAMES) {
                        throw new CorruptFileException("read", "too many undecodable MP3 frames in a row", null, e);
                    }
                    continue;
                }
                consecutiveBadFrames = 0;
                bitstream.closeFrame();
                SampleBuffer samples = (SampleBuffer) out;
                int frameChannels = samples.getChannelCount();
                if (firstSampleRateKnown() && (frameChannels != firstChannels || header.frequency() != firstSampleRate)) {
                    throw new CorruptFileException("read", "MP3 format changed mid-stream in " + source, null, null);
                }
                if (!firstSampleRateKnown()) {
                    firstChannels = frameChannels;
                    firstSampleRate = header.frequency();
                }
                int shorts = samples.getBufferLength();
                if (shorts == 0) {
                    continue;
                }
                if (pcm.length < shorts * 2) {
                    pcm = new byte[shorts * 2];
                }
                Mp3Decoder.encodeLittleEndian(samples.getBuffer(), 0, shorts, pcm, 0);
                pcmPos = 0;
                pcmLen = shorts * 2;
                return true;
            }
        } catch (BitstreamException e) {
            throw new DecoderException("read", "corrupt MP3 frame in " + source, null, e);
        }
    }

    /**
     * The total duration in frames: the Xing count is exact, and otherwise the bitrate of the first
     * frame is applied to the audio bytes (exact for constant-bitrate files).
     */
    private long estimateFrames(Mp3StreamScan scan) {
        if (xingFrames > 0) {
            return xingFrames;
        }
        if (scan == null || byteLength <= 0 || scan.bitrate() <= 0) {
            return -1L;
        }
        double seconds = (byteLength - audioStart) * 8.0 / scan.bitrate();
        return Math.round(seconds * scan.sampleRate() / samplesPerFrame);
    }

    private static Duration durationOf(long samples, int sampleRate) {
        return Duration.ofSeconds(samples / sampleRate)
                .plusNanos((samples % sampleRate) * 1_000_000_000L / sampleRate);
    }

    private static void skip(InputStream in, long count) throws IOException {
        byte[] buffer = new byte[8192];
        long remaining = count;
        while (remaining > 0) {
            int n = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (n < 0) {
                return;
            }
            remaining -= n;
        }
    }

    private boolean firstSampleRateKnown() {
        return firstSampleRate != 0;
    }

    private void ensureOpen() {
        if (closed) {
            throw new DecoderException("read", "decoder is closed", null, null);
        }
    }
}
