package io.github.sinaraz.audio;

import java.util.concurrent.locks.LockSupport;
import javax.sound.sampled.AudioFormat;

import io.github.sinaraz.error.AudioDeviceException;

/**
 * An {@link AudioOutput} that behaves like a real device without making sound.
 *
 * <p>It has a finite buffer that drains at the real-time rate while running. {@link #write} blocks
 * when the buffer is full, exactly as {@code SourceDataLine} does. This lets the console demo and
 * the timing behaviour of pause, seek and end-of-track be exercised on machines with no sound card
 * (select it with {@code --null-audio}).
 *
 * <p>Single-threaded use only, like every other {@link AudioOutput}.
 */
public final class SimulatedAudioOutput implements AudioOutput {

    private final int capacityBytes;
    private long bytesPerSecond;
    private int frameSize;
    private long written;
    private long drained;
    private long lastNanos;
    private boolean running;
    private boolean open;

    /**
     * Creates a simulated output.
     *
     * @param capacityBytes size of the simulated device buffer in bytes; at least 4096
     */
    public SimulatedAudioOutput(int capacityBytes) {
        if (capacityBytes < 4096) {
            throw new IllegalArgumentException("capacityBytes must be >= 4096");
        }
        this.capacityBytes = capacityBytes;
    }

    @Override
    public void open(AudioFormat format) {
        this.frameSize = format.getFrameSize();
        this.bytesPerSecond = (long) (format.getSampleRate() * frameSize);
        this.written = 0L;
        this.drained = 0L;
        this.lastNanos = System.nanoTime();
        this.running = false;
        this.open = true;
    }

    @Override
    public void write(byte[] data, int offset, int length) throws AudioDeviceException {
        requireOpen("write");
        int remaining = length;
        while (remaining > 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new AudioDeviceException("write", "interrupted while writing to simulated output", null);
            }
            if (!open) {
                throw new AudioDeviceException("write", "simulated audio output was closed", null);
            }
            advance();
            long buffered = written - drained;
            long free = capacityBytes - buffered;
            if (free <= 0) {
                long waitNanos = Math.max(1L, (long) remaining * 1_000_000_000L / bytesPerSecond);
                LockSupport.parkNanos(Math.min(waitNanos, 20_000_000L));
                continue;
            }
            int chunk = (int) Math.min(free, remaining);
            written += chunk;
            remaining -= chunk;
        }
    }

    @Override
    public void start() {
        advance();
        running = true;
    }

    @Override
    public void stop() {
        advance();
        running = false;
    }

    @Override
    public void flush() {
        advance();
        drained = written;
    }

    @Override
    public void drain() throws AudioDeviceException {
        requireOpen("drain");
        if (!running) {
            start();
        }
        advance();
        while (written - drained > 0) {
            long waitNanos = (written - drained) * 1_000_000_000L / bytesPerSecond;
            LockSupport.parkNanos(Math.min(Math.max(waitNanos, 1L), 50_000_000L));
            advance();
        }
    }

    @Override
    public long bufferedFrames() {
        if (!open || frameSize <= 0) {
            return 0L;
        }
        advance();
        return (written - drained) / frameSize;
    }

    @Override
    public boolean applyVolume(double linearVolume, boolean muted) {
        return false;
    }

    @Override
    public void close() {
        open = false;
        running = false;
    }

    private void advance() {
        long now = System.nanoTime();
        if (running) {
            long elapsedBytes = (now - lastNanos) * bytesPerSecond / 1_000_000_000L;
            drained = Math.min(written, drained + elapsedBytes);
        }
        lastNanos = now;
    }

    private void requireOpen(String operation) throws AudioDeviceException {
        if (!open) {
            throw new AudioDeviceException(operation, "simulated audio output is not open", null);
        }
    }
}
