package io.github.sinaraz.support;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Polls a condition with a deadline. Used instead of fixed sleeps so tests stay fast and reliable. */
public final class Await {

    private Await() {}

    public static void until(String what, Duration timeout, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out after " + timeout + " waiting for: " + what);
            }
            Thread.sleep(5);
        }
    }
}
