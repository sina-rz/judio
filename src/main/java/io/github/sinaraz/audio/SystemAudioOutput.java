package io.github.sinaraz.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.BooleanControl;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import io.github.sinaraz.error.AudioDeviceException;
import io.github.sinaraz.internal.processing.DecibelMapping;

/**
 * {@link AudioOutput} backed by a streaming {@link SourceDataLine} from the default mixer.
 *
 * <p>On open, the line's {@code MASTER_GAIN} and {@code MUTE} controls are probed. When present,
 * volume is applied as a dB value mapped from the linear user volume (see
 * {@link DecibelMapping#toControlDb}), and mute uses the hardware switch. Otherwise the engine
 * falls back to software gain. Both paths are covered by tests.
 */
public final class SystemAudioOutput implements AudioOutput {

    private final int bufferBytes;
    private SourceDataLine line;
    private AudioFormat format;
    private FloatControl masterGain;
    private BooleanControl mute;

    /**
     * Creates an output for the default sound device.
     *
     * @param bufferBytes size of the device buffer in bytes
     */
    public SystemAudioOutput(int bufferBytes) {
        if (bufferBytes < 4096) {
            throw new IllegalArgumentException("bufferBytes must be >= 4096");
        }
        this.bufferBytes = bufferBytes;
    }

    @Override
    public void open(AudioFormat requested) throws AudioDeviceException {
        close();
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, requested, bufferBytes);
        if (!AudioSystem.isLineSupported(info)) {
            throw new AudioDeviceException("open",
                    "no output line supports " + requested + " with a " + bufferBytes + "-byte buffer", null);
        }
        try {
            SourceDataLine opened = (SourceDataLine) AudioSystem.getLine(info);
            opened.open(requested, bufferBytes);
            this.line = opened;
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            throw new AudioDeviceException("open", "audio output device unavailable: " + e.getMessage(), e);
        }
        this.format = requested;
        this.masterGain = line.isControlSupported(FloatControl.Type.MASTER_GAIN)
                ? (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN)
                : null;
        this.mute = line.isControlSupported(BooleanControl.Type.MUTE)
                ? (BooleanControl) line.getControl(BooleanControl.Type.MUTE)
                : null;
    }

    @Override
    public void write(byte[] data, int offset, int length) throws AudioDeviceException {
        requireOpen("write");
        int off = offset;
        int remaining = length;
        while (remaining > 0) {
            int n = line.write(data, off, remaining);
            if (n <= 0) {
                throw new AudioDeviceException("write", "audio device stopped accepting data", null);
            }
            off += n;
            remaining -= n;
        }
    }

    @Override
    public void start() {
        if (line != null) {
            line.start();
        }
    }

    @Override
    public void stop() {
        if (line != null) {
            line.stop();
        }
    }

    @Override
    public void flush() {
        if (line != null) {
            line.flush();
        }
    }

    @Override
    public void drain() throws AudioDeviceException {
        requireOpen("drain");
        line.drain();
    }

    @Override
    public long bufferedFrames() {
        if (line == null) {
            return 0L;
        }
        long used = line.getBufferSize() - line.available();
        return Math.max(0L, used / format.getFrameSize());
    }

    @Override
    public boolean applyVolume(double linearVolume, boolean muted) {
        if (line == null) {
            return false;
        }
        if (mute != null) {
            mute.setValue(muted);
        }
        if (masterGain != null) {
            float db = DecibelMapping.toControlDb(linearVolume,
                    masterGain.getMinimum(), masterGain.getMaximum());
            masterGain.setValue(db);
        }
        boolean volumeInHardware = masterGain != null;
        boolean muteInHardware = !muted || mute != null;
        return volumeInHardware && muteInHardware;
    }

    @Override
    public void close() {
        if (line != null) {
            line.stop();
            line.close();
            line = null;
            masterGain = null;
            mute = null;
            format = null;
        }
    }

    private void requireOpen(String operation) throws AudioDeviceException {
        if (line == null) {
            throw new AudioDeviceException(operation, "audio output is not open", null);
        }
    }
}
