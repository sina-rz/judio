package io.github.sinaraz.model;

import java.nio.ByteOrder;
import java.time.Duration;
import java.util.Objects;

/**
 * Description of the canonical PCM stream a decoder produces.
 *
 * @param duration      total duration; {@link Duration#ZERO} only if the source is empty
 * @param sampleRate    frames per second, e.g. 44100
 * @param channels      1 (mono) or 2 (stereo) in the canonical output
 * @param sampleSizeBits bits per sample in the canonical output (always 16 for the built-in decoders)
 * @param frameSize     bytes per PCM frame in the canonical output
 * @param frameCount    total frames in the stream
 * @param encoding      PCM encoding name, e.g. {@code PCM_SIGNED}
 * @param byteOrder     byte order of canonical samples (always little-endian)
 * @param seekable      whether {@code AudioDecoder.seek} can reposition the stream
 * @param sourceFormat  human-readable description of the source format, e.g. {@code "MPEG1 L3 128kbps"}
 * @param decoderName   name of the decoder that produced this information
 */
public record AudioInfo(
        Duration duration,
        int sampleRate,
        int channels,
        int sampleSizeBits,
        int frameSize,
        long frameCount,
        String encoding,
        ByteOrder byteOrder,
        boolean seekable,
        String sourceFormat,
        String decoderName) {

    /** Validates the format and duration fields; throws {@link IllegalArgumentException} if they are inconsistent. */
    public AudioInfo {
        Objects.requireNonNull(duration, "duration");
        Objects.requireNonNull(encoding, "encoding");
        Objects.requireNonNull(byteOrder, "byteOrder");
        Objects.requireNonNull(sourceFormat, "sourceFormat");
        Objects.requireNonNull(decoderName, "decoderName");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive: " + sampleRate);
        }
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("channels must be 1 or 2: " + channels);
        }
        if (frameSize != channels * sampleSizeBits / 8) {
            throw new IllegalArgumentException("frameSize inconsistent with channels and sample size");
        }
        if (frameCount < 0) {
            throw new IllegalArgumentException("frameCount must not be negative");
        }
    }
}
