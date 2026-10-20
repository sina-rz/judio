package io.github.sinaraz.internal.lifecycle;

import java.time.Duration;
import java.util.Objects;

/** A monotonic end-to-end deadline shared by sequential resource shutdown phases. */
public final class CloseDeadline {

    private final long deadlineNanos;

    private CloseDeadline(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }

    public static CloseDeadline after(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long timeoutNanos;
        try {
            timeoutNanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            timeoutNanos = Long.MAX_VALUE;
        }
        return new CloseDeadline(System.nanoTime() + timeoutNanos);
    }

    /** Remaining duration, clamped at zero. */
    public Duration remaining() {
        return Duration.ofNanos(remainingNanos());
    }

    /** Remaining nanoseconds, clamped at zero. */
    public long remainingNanos() {
        return Math.max(0L, deadlineNanos - System.nanoTime());
    }

    /** Waits for a thread only for the time remaining on this deadline. */
    public boolean join(Thread thread) throws InterruptedException { //maybe invert this?
        Objects.requireNonNull(thread, "thread");
        long remaining = remainingNanos();
        if (remaining > 0L && thread.isAlive()) {
            long millis = remaining / 1_000_000L;
            int nanos = (int) (remaining % 1_000_000L);
            thread.join(millis, nanos);
        }
        return !thread.isAlive();
    }
}
