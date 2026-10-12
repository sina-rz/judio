package io.github.sinaraz.model;

import java.util.Locale;

/** How the queue behaves when the current track ends or when stepping past the end. */
public enum RepeatMode {
    /** Stop after the last queued track. */
    OFF,
    /** Replay the current track when it ends. */
    ONE,
    /** Wrap around from the last track to the first. */
    ALL;

    /**
     * Parses {@code off}, {@code one} or {@code all}, case-insensitively.
     *
     * @param text the word to parse
     * @return the matching mode
     * @throws IllegalArgumentException if the word is none of the three
     */
    public static RepeatMode parse(String text) {
        return switch (text.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "one" -> ONE;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException("unknown repeat mode: " + text);
        };
    }
}
