package io.github.sinaraz.internal.decoder;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.error.DecoderException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.AudioInfo;

/**
 * Adapter over {@code javax.sound.sampled} for WAV, AIFF and AU files.
 *
 * <p>The file is opened with {@link AudioSystem#getAudioInputStream(InputStream)} and then converted
 * to canonical signed 16-bit little-endian PCM with the source's rate and channel count. Java Sound's
 * PCM converters handle 8-bit unsigned, 24-bit, 32-bit, big-endian and µ-law/A-law inputs.
 *
 * <p><b>Seeking</b> reopens the file and skips forward to the target frame. This is exact (frame
 * accurate) and costs time proportional to the target position. It is acceptable for local files.
 */
public final class JavaSoundDecoder implements AudioDecoder {

    private static final String NAME = "JavaSound";
    private static final int SKIP_CHUNK = 64 * 1024;

    private final Path path;
    private final AudioFormat target;
    private final int frameSize;
    private final long frameLength;      // -1 when the container does not state it
    private final AudioInfo info;
    private AudioInputStream stream;
    private long framePos;
    private boolean closed;

    public JavaSoundDecoder(Path path) throws IOException {
        this.path = Objects.requireNonNull(path, "path");
        AudioInputStream probe = openSource();
        try {
            AudioFormat source = probe.getFormat();
            int channels = source.getChannels();
            if (channels < 1 || channels > 2) {
                throw new UnsupportedAudioFormatException("open",
                        "only mono and stereo are supported, file has " + channels + " channels", path, null);
            }
            float rate = source.getSampleRate();
            if (rate <= 0) {
                throw new UnsupportedAudioFormatException("open", "unknown sample rate", path, null);
            }
            this.target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels,
                    channels * 2, rate, false);
            this.frameSize = target.getFrameSize();
            this.frameLength = probe.getFrameLength();
            if (!AudioSystem.isConversionSupported(target, source)) {
                throw new UnsupportedAudioFormatException("open",
                        "no PCM conversion from " + source + " to canonical PCM16", path, null);
            }
            this.stream = AudioSystem.getAudioInputStream(target, probe);
            long frames = frameLength >= 0 ? frameLength : 0L;
            Duration duration = frames > 0
                    ? Duration.ofNanos(Math.round(frames * 1_000_000_000.0 / rate))
                    : Duration.ZERO;
            this.info = new AudioInfo(
                    duration,
                    (int) rate,
                    channels,
                    16,
                    frameSize,
                    frames,
                    "PCM_SIGNED",
                    ByteOrder.LITTLE_ENDIAN,
                    true,
                    source.getEncoding() + " " + source.getSampleSizeInBits() + "-bit",
                    NAME);
        } catch (RuntimeException e) {
            try {
                probe.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
        this.framePos = 0L;
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
                n -= rem; // drop a trailing partial frame at end of stream
                break;
            }
            if (k == 0 && ++zeroReads > 1000) {
                throw new IOException("audio stream returned no data repeatedly: " + path);
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

    @Override
    public void seek(Duration position) throws IOException {
        ensureOpen();
        Objects.requireNonNull(position, "position");
        long rate = info.sampleRate();
        long target = Math.round(Math.max(0.0, position.toNanos() / 1_000_000_000.0) * rate);
        if (frameLength >= 0) {
            target = Math.min(target, frameLength);
        }
        // Build the replacement first: a failed reopen must not leave this
        // decoder holding a closed stream.
        AudioInputStream replacement = reopenConverted();
        AudioInputStream old = stream;
        stream = replacement;
        old.close();
        long toSkip = target * frameSize;
        byte[] buf = new byte[SKIP_CHUNK];
        while (toSkip > 0) {
            int got = stream.readNBytes(buf, 0, (int) Math.min(buf.length, toSkip));
            if (got == 0) {
                break; // target is beyond the end of the stream
            }
            toSkip -= got;
        }
        framePos = (target * frameSize - toSkip) / frameSize;
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
            stream.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new DecoderException("read", "decoder is closed", path, null);
        }
    }

    private AudioInputStream reopenConverted() throws IOException {
        AudioInputStream source = openSource();
        try {
            return AudioSystem.getAudioInputStream(target, source);
        } catch (RuntimeException e) {
            source.close();
            throw e;
        }
    }

    private AudioInputStream openSource() throws IOException {
        InputStream raw = new BufferedInputStream(Files.newInputStream(path));
        try {
            return AudioSystem.getAudioInputStream(raw);
        } catch (UnsupportedAudioFileException e) {
            raw.close();
            throw new UnsupportedAudioFormatException("open", "not a supported WAV/AIFF/AU file: " + path.getFileName(),
                    path, e);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }
}
