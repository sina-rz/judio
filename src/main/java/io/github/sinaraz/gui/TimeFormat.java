package io.github.sinaraz.gui;

import java.time.Duration;
import java.util.Locale;

/** Formats durations as {@code m:ss} or {@code h:mm:ss}. */
final class TimeFormat {

    private TimeFormat() {}

    static String format(Duration duration) {
        if (duration == null) {
            return "--:--";
        }
        long total = Math.max(0L, duration.toSeconds());
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%02d:%02d", minutes, seconds);
    }
}
