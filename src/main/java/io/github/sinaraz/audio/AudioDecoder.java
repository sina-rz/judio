package io.github.sinaraz.audio;

import java.io.IOException;
import java.time.Duration;

import io.github.sinaraz.model.AudioInfo;

/**
 * A pull-based source of canonical PCM.
 *
 * <p>The canonical format is signed 16-bit little-endian, interleaved, with the source's sample
 * rate and 1 or 2 channels. Every decoder converts to this format, so the rest of the engine never
 * sees codec-specific data. {@link #read} returns whole frames only.
 *
 * <p>Decoders are used by one thread at a time. The engine's audio worker is the only caller.
 */
public interface AudioDecoder extends AutoCloseable {

    /**
     * Format and duration of the canonical output. Constant after construction.
     *
     * @return the stream description
     */
    AudioInfo audioInfo();

    /**
     * Reads up to {@code length} bytes of canonical PCM.
     *
     * @param buffer destination array
     * @param offset first index in {@code buffer} to write
     * @param length must be at least one frame; the number of bytes returned is a multiple of the frame size
     * @return bytes read ({@code > 0}), or {@code -1} at end of stream
     * @throws IOException if the source cannot be read or is corrupt
     */
    int read(byte[] buffer, int offset, int length) throws IOException;

    /**
     * Repositions the stream so that the next {@link #read} returns the frame at {@code position}.
     * Positions past the end leave the decoder at end of stream.
     *
     * @param position target offset from the start of the track
     * @throws IOException if the source cannot be repositioned
     * @throws io.github.sinaraz.error.SeekNotSupportedException if {@link #isSeekable()} is {@code false}
     */
    void seek(Duration position) throws IOException;

    /**
     * Whether {@link #seek} is supported. Non-seekable sources report {@code false}.
     *
     * @return {@code true} if {@link #seek} can reposition the stream
     */
    boolean isSeekable();

    /**
     * Index of the next frame that {@link #read} will return, counted from the start of the file.
     * Used by the engine to compute the playback position.
     *
     * @return the index of the next frame to be read
     */
    long framePosition();

    /** Releases the underlying file or stream. Safe to call more than once. */
    @Override
    void close() throws IOException;
}
