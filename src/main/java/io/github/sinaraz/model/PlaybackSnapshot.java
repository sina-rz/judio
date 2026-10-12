package io.github.sinaraz.model;

import java.time.Duration;

/**
 * Immutable view of the player at one instant.
 *
 * @param track        the loaded track, or {@code null}
 * @param state        current state machine state
 * @param position     current position, clamped to {@code [0, duration]}
 * @param duration     duration of the loaded track, or {@link Duration#ZERO}
 * @param volume       user volume in {@code [0.0, 1.0]}
 * @param muted        whether output is muted
 * @param speed        playback speed factor
 * @param pan          stereo pan in {@code [-1.0, 1.0]}
 * @param preservePitch whether speed changes keep the pitch (time-stretching) instead of changing it
 * @param queueIndex   index of the current queue entry, or {@code -1}
 * @param queueSize    number of queue entries
 * @param repeat       repeat mode
 * @param shuffle      whether shuffle is enabled
 * @param error        most recent error for the loaded track, or {@code null}
 */
public record PlaybackSnapshot(
        Track track,
        PlaybackState state,
        Duration position,
        Duration duration,
        double volume,
        boolean muted,
        double speed,
        double pan,
        boolean preservePitch,
        int queueIndex,
        int queueSize,
        RepeatMode repeat,
        boolean shuffle,
        PlaybackError error) {

    /**
     * Checks that the required fields are present.
     *
     * @throws NullPointerException if a required field is {@code null}
     */
    public PlaybackSnapshot {
        java.util.Objects.requireNonNull(state, "state");
        java.util.Objects.requireNonNull(position, "position");
        java.util.Objects.requireNonNull(duration, "duration");
        java.util.Objects.requireNonNull(repeat, "repeat");
    }
}
