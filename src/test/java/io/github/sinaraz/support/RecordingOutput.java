package io.github.sinaraz.support;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.sound.sampled.AudioFormat;

import io.github.sinaraz.audio.AudioOutput;
import io.github.sinaraz.error.AudioDeviceException;

/**
 * Output that accepts everything instantly and keeps the bytes. Used to check exact PCM content.
 * Set {@code hardwareVolume} to simulate a device that applies volume and mute itself.
 */
public final class RecordingOutput implements AudioOutput {

    private final boolean hardwareVolume;
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private final List<String> volumeCalls = new ArrayList<>();
    private AudioFormat format;

    public RecordingOutput() {
        this(false);
    }

    public RecordingOutput(boolean hardwareVolume) {
        this.hardwareVolume = hardwareVolume;
    }

    public synchronized byte[] recorded() {
        return bytes.toByteArray();
    }

    public synchronized List<String> volumeCalls() {
        return List.copyOf(volumeCalls);
    }

    public AudioFormat format() {
        return format;
    }

    @Override
    public void open(AudioFormat format) {
        this.format = format;
    }

    @Override
    public synchronized void write(byte[] data, int offset, int length) throws AudioDeviceException {
        bytes.write(data, offset, length);
    }

    @Override
    public void start() {
    }

    @Override
    public void stop() {
    }

    @Override
    public void flush() {
    }

    @Override
    public void drain() {
    }

    @Override
    public long bufferedFrames() {
        return 0;
    }

    @Override
    public synchronized boolean applyVolume(double linearVolume, boolean muted) {
        volumeCalls.add(linearVolume + "," + muted);
        return hardwareVolume;
    }

    @Override
    public void close() {
    }
}
