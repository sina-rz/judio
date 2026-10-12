package io.github.sinaraz.internal.decoder;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
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
import io.github.sinaraz.model.AudioInfo;

/**
 * MP3 adapter over JLayer 1.0.1 (pure Java, LGPL).
 *
 * <h2>Capabilities</h2>
 * <ul>
 *   <li><b>Formats:</b> MPEG-1/2/2.5 Layer I, II and III. Layer III is the common case.</li>
 *   <li><b>Streaming:</b> frames are decoded on demand; the file is never fully loaded.</li>
 *   <li><b>Duration:</b> computed exactly from frame headers during construction (one pass over frame
 *       headers without decoding). Accurate for CBR and VBR files, but construction costs one read of
 *       the file.</li>
 *   <li><b>Seeking:</b> frame accurate. The decoder reopens the file, skips frame headers up to two
 *       frames before the target (pre-roll, so the bit reservoir and synthesis filter are primed),
 *       decodes and discards the pre-roll, then discards samples up to the exact target.</li>
 *   <li><b>ID3v2 tags:</b> skipped by JLayer; metadata is read separately by the metadata package.</li>
 * </ul>
 *
 * <h2>Limitations</h2>
 * <ul>
 *   <li>Output is 16-bit; JLayer's decoder works in 16-bit internally.</li>
 *   <li>Decoding is not sample-accurate for files with a gapless encoder delay/padding (LAME tag)
 *       trimmed out: leading and trailing encoder padding is decoded and played.</li>
 *   <li>A frame that JLayer cannot decode is skipped (its audio is lost for that 20-30 ms). If
 *       {@value #MAX_CONSECUTIVE_BAD_FRAMES} frames in a row fail, the track is reported as corrupt.</li>
 * </ul>
 */
public final class Mp3Decoder implements AudioDecoder {

    static final String NAME = "JLayer";
    private static final int MAX_SAMPLES_PER_FRAME = 1152;
    /**
     * Frames decoded and discarded before a seek target. A frame's main data can start up to 511 bytes
     * before it, which may lie two or more frames back, so four frames of pre-roll are needed to
     * rebuild the bit reservoir and the synthesis overlap.
     */
    private static final int PREROLL_FRAMES = 4;
    private static final int MAX_CONSECUTIVE_BAD_FRAMES = 16;

    private final Path path;
    private final AudioInfo info;
    private final int channels;
    private final int frameBytes;
    private final long totalFrames;
    private final long fileSize;
    /** Byte offset where frame {@code i}'s header search starts (a frame boundary or garbage before it). */
    private final long[] frameOffsets;
    /** Sample index (per channel) of the first sample of frame {@code i}. */
    private final long[] frameStarts;

    private FileChannel channel;
    private InputStream in;
    private Bitstream bitstream;
    private Decoder decoder;
    private byte[] pcm = new byte[0];
    private int pcmPos;
    private int pcmLen;
    private long framePos;
    private int consecutiveBadFrames;
    private boolean closed;

    public Mp3Decoder(Path path) throws IOException {
        this.path = Objects.requireNonNull(path, "path");
        Scan scan = scan(path);
        this.channels = scan.channels();
        this.frameBytes = channels * 2;
        this.totalFrames = scan.totalFrames();
        this.fileSize = scan.fileSize();
        this.frameOffsets = scan.frameOffsets();
        this.frameStarts = scan.frameStarts();
        Duration duration = Duration.ofNanos(Math.round(totalFrames * 1_000_000_000.0 / scan.sampleRate()));
        this.info = new AudioInfo(
                duration,
                scan.sampleRate(),
                channels,
                16,
                frameBytes,
                totalFrames,
                "PCM_SIGNED",
                ByteOrder.LITTLE_ENDIAN,
                true,
                "MPEG audio (JLayer: Layer I/II/III)",
                NAME);
        openAt(0L);
    }

    @Override
    public AudioInfo audioInfo() {
        return info;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        ensureOpen();
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

    @Override
    public void seek(Duration position) throws IOException {
        ensureOpen();
        Objects.requireNonNull(position, "position");
        long target = Math.round(Math.max(0.0, position.toNanos() / 1_000_000_000.0) * info.sampleRate());
        target = Math.min(target, totalFrames);

        closeStreams();
        if (target >= totalFrames) {
            openAt(fileSize);
            pcmPos = 0;
            pcmLen = 0;
            framePos = totalFrames;
            return;
        }
        // Jump to the frame index entry PREROLL_FRAMES before the target, so the bit reservoir and
        // the synthesis filter are primed by the time the target frame is decoded.
        int start = Math.max(0, frameContaining(target) - PREROLL_FRAMES);
        openAt(frameOffsets[start]);

        long cursor = frameStarts[start];
        try {
            Header header = bitstream.readFrame();
            while (header != null) {
                Obuffer out = decoder.decodeFrame(header, bitstream);
                bitstream.closeFrame();
                SampleBuffer samples = (SampleBuffer) out;
                // Count by the frame's nominal length, not by the decoder's buffer length: the first
                // frame decoded after a seek can come back short while its bit reservoir is rebuilt.
                int perChannel = samplesPerFrame(header);
                if (cursor + perChannel <= target) {
                    cursor += perChannel;
                    header = bitstream.readFrame();
                    continue;
                }
                if (samples.getBufferLength() < perChannel * channels) {
                    throw new DecoderException("seek", "MP3 frame at the seek target could not be decoded",
                            path, null);
                }
                int skip = (int) (target - cursor);
                cursor = target;
                ensurePcmCapacity(perChannel * channels * 2);
                int fromShort = skip * channels;
                int count = perChannel * channels - fromShort;
                encodeLittleEndian(samples.getBuffer(), fromShort, count, pcm, 0);
                pcmPos = 0;
                pcmLen = count * 2;
                break;
            }
        } catch (BitstreamException e) {
            throw new DecoderException("seek", "corrupt MP3 frame while seeking", path, e);
        } catch (javazoom.jl.decoder.DecoderException e) {
            throw new DecoderException("seek", "MP3 decoder failed while seeking", path, e);
        }
        framePos = cursor;
    }

    /** Index of the frame whose sample range contains {@code sample}. Requires {@code sample < totalFrames}. */
    private int frameContaining(long sample) {
        int lo = 0;
        int hi = frameStarts.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (frameStarts[mid] <= sample) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public long framePosition() {
        return framePos;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            closeStreams();
        }
    }

    // ---------- internals ----------

    private void openAt(long offset) throws IOException {
        channel = FileChannel.open(path, StandardOpenOption.READ);
        channel.position(offset);
        in = new BufferedInputStream(Channels.newInputStream(channel));
        bitstream = new Bitstream(in);
        decoder = new Decoder();
        pcmPos = 0;
        pcmLen = 0;
    }

    private void closeStreams() throws IOException {
        try {
            if (bitstream != null) {
                bitstream.close();
            }
        } catch (BitstreamException e) {
            throw new IOException("failed to close MP3 bitstream: " + path, e);
        } finally {
            bitstream = null;
            if (in != null) {
                in.close();
                in = null;
            }
            if (channel != null) {
                channel.close();
                channel = null;
            }
        }
    }

    private boolean decodeNextFrame() {
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
                    // JLayer can fail on a damaged frame. Skip it rather than stop the track,
                    // but give up if many frames in a row are bad: then the file is really broken.
                    bitstream.closeFrame();
                    if (++consecutiveBadFrames > MAX_CONSECUTIVE_BAD_FRAMES) {
                        throw new CorruptFileException("read", "too many undecodable MP3 frames in a row", path, e);
                    }
                    continue;
                }
                consecutiveBadFrames = 0;
                bitstream.closeFrame();
                SampleBuffer samples = (SampleBuffer) out;
                if (samples.getChannelCount() != channels) {
                    throw new CorruptFileException("read",
                            "channel count changed mid-stream: " + samples.getChannelCount(), path, null);
                }
                int shorts = samples.getBufferLength();
                if (shorts == 0) {
                    continue;
                }
                ensurePcmCapacity(shorts * 2);
                encodeLittleEndian(samples.getBuffer(), 0, shorts, pcm, 0);
                pcmPos = 0;
                pcmLen = shorts * 2;
                return true;
            }
        } catch (BitstreamException e) {
            throw new DecoderException("read", "corrupt MP3 frame", path, e);
        }
    }

    private void ensurePcmCapacity(int bytes) {
        if (pcm.length < bytes) {
            pcm = new byte[bytes];
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new DecoderException("read", "decoder is closed", path, null);
        }
    }

    static void encodeLittleEndian(short[] src, int from, int count, byte[] dst, int dstOff) {
        for (int i = 0; i < count; i++) {
            short s = src[from + i];
            dst[dstOff + 2 * i] = (byte) s;
            dst[dstOff + 2 * i + 1] = (byte) (s >> 8);
        }
    }

    /** Samples per channel contained in one frame of this header. */
    static int samplesPerFrame(Header header) {
        return switch (header.layer()) {
            case 1 -> 384;
            case 2 -> 1152;
            default -> header.version() == Header.MPEG1 ? 1152 : 576;
        };
    }

    private record Scan(int sampleRate, int channels, long totalFrames, long fileSize,
                        long[] frameOffsets, long[] frameStarts) {}

    /**
     * Reads every frame header once to learn the stream format, the exact length, and a seek index
     * (byte offset and first sample of every frame). No samples are decoded.
     */
    private static Scan scan(Path path) throws IOException {
        int rate = 0;
        int channels = 0;
        long total = 0L;
        long size = Files.size(path);
        long[] offsets = new long[1024];
        long[] starts = new long[1024];
        int count = 0;
        // Frame offsets are accumulated from the frame sizes JLayer reports. Counting the bytes read
        // instead would be wrong: JLayer reads ahead into its own buffer.
        long nextOffset = id3v2Length(path);
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path))) {
            Bitstream bs = new Bitstream(file);
            try {
                while (true) {
                    Header h = bs.readFrame();
                    if (h == null) {
                        break;
                    }
                    if (rate == 0) {
                        rate = h.frequency();
                        channels = h.mode() == Header.SINGLE_CHANNEL ? 1 : 2;
                    }
                    if (count == offsets.length) {
                        offsets = Arrays.copyOf(offsets, count * 2);
                        starts = Arrays.copyOf(starts, count * 2);
                    }
                    offsets[count] = nextOffset;
                    starts[count] = total;
                    count++;
                    // JLayer's framesize excludes the 4-byte header; the frame occupies framesize + 4 bytes.
                    nextOffset += h.framesize + 4L;
                    total += samplesPerFrame(h);
                    bs.closeFrame();
                }
            } catch (BitstreamException e) {
                // Trailing garbage after the last valid frame is normal in the wild; keep what we found.
                if (total == 0L) {
                    throw new CorruptFileException("open", "no valid MP3 frames found", path, e);
                }
            } finally {
                bs.close();
            }
        } catch (BitstreamException e) {
            throw new CorruptFileException("open", "cannot read MP3 stream", path, e);
        }
        if (total == 0L || rate <= 0) {
            throw new CorruptFileException("open", "no valid MP3 frames found", path, null);
        }
        return new Scan(rate, channels, total, size,
                Arrays.copyOf(offsets, count), Arrays.copyOf(starts, count));
    }

    /** Length of a leading ID3v2 tag in bytes, including its header and optional footer; 0 if none. */
    private static long id3v2Length(Path path) throws IOException {
        try (SeekableByteChannel channel = Files.newByteChannel(path, StandardOpenOption.READ)) {
            ByteBuffer head = ByteBuffer.allocate(10);
            if (channel.read(head) < 10) {
                return 0L;
            }
            head.flip();
            if (head.get(0) != 'I' || head.get(1) != 'D' || head.get(2) != '3') {
                return 0L;
            }
            long size = 0L;
            for (int i = 6; i < 10; i++) {
                size = (size << 7) | (head.get(i) & 0x7F);  // syncsafe integer
            }
            boolean footer = (head.get(5) & 0x10) != 0;
            return 10L + size + (footer ? 10L : 0L);
        }
    }
}
