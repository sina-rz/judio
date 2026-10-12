package io.github.sinaraz.audio;

import javax.sound.sampled.AudioFormat;

import io.github.sinaraz.error.AudioDeviceException;

/**
 * Destination for canonical PCM. The engine's audio worker is the only caller, so
 * implementations need not be thread-safe.
 *
 * <p>{@link #write} blocks until the data is accepted by the device buffer. That blocking is the
 * playback clock and the only backpressure in the system.
 */
public interface AudioOutput extends AutoCloseable {

    /**
     * Opens the device for {@code format}. Called again after a format change.
     *
     * @param format canonical PCM16 format of the audio that will be written
     * @throws AudioDeviceException if the device cannot be opened for this format
     */
    void open(AudioFormat format) throws AudioDeviceException;

    /**
     * Writes all {@code length} bytes, blocking as needed. Implementations must copy the data before
     * returning, because the caller reuses the array.
     *
     * @param data   source bytes
     * @param offset first index in {@code data}
     * @param length number of bytes to write
     * @throws AudioDeviceException if the device fails
     */
    void write(byte[] data, int offset, int length) throws AudioDeviceException;

    /** Starts consuming buffered audio. The playback clock runs only while the output is started. */
    void start();

    /** Pauses consumption. Buffered audio is kept and resumes with {@link #start()}. */
    void stop();

    /** Discards buffered audio immediately. Used for stop and seek. */
    void flush();

    /** Blocks until all buffered audio has been played. Used at natural end of track. */
    void drain() throws AudioDeviceException;

    /**
     * Frames accepted by the device but not yet played. Used to compute the playback position.
     *
     * @return the number of frames still queued in the device
     */
    long bufferedFrames();

    /**
     * Applies volume and mute in hardware when the device supports it.
     *
     * @return {@code true} if the device applied both volume and mute, so the caller must not
     *         apply them in software; {@code false} if software processing is required
     * @param linearVolume volume in [0, 1]
     * @param muted        whether output should be silent
     */
    boolean applyVolume(double linearVolume, boolean muted);

    @Override
    void close();
}
