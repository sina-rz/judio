package io.github.sinaraz.model;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Explicit playback state machine.
 *
 * <pre>
 *  EMPTY ──load──▶ LOADING ──ok──▶ READY ──play──▶ PLAYING ◀──resume── PAUSED
 *                     │                │  ▲            │  ──pause──▶     ▲
 *                     └──fail──▶ ERROR │  └─seek done──┤                 │
 *                                      │  SEEKING ◀────┴── seek ─────────┘
 *  PLAYING ──stop──▶ STOPPING ──▶ STOPPED      PLAYING ──EOF──▶ ENDED
 *  any non-terminal ──close──▶ CLOSED (terminal)
 * </pre>
 *
 * <p>{@link #canTransitionTo(PlaybackState)} encodes the complete table. The engine
 * validates every public operation against it before changing state.
 */
public enum PlaybackState {
    /** No track is loaded. */
    EMPTY,
    /** A track is being opened. */
    LOADING,
    /** A track is loaded and ready to play. */
    READY,
    /** Audio is playing. */
    PLAYING,
    /** Playback is paused at the current position. */
    PAUSED,
    /** The position is being changed. */
    SEEKING,
    /** Playback is being stopped. */
    STOPPING,
    /** Playback is stopped; the track stays loaded. */
    STOPPED,
    /** The track played to its end. */
    ENDED,
    /** The last track failed. */
    ERROR,
    /** The player has been closed and cannot be used again. */
    CLOSED;

    private static final Map<PlaybackState, Set<PlaybackState>> TRANSITIONS = new EnumMap<>(PlaybackState.class);

    static {
        TRANSITIONS.put(EMPTY, EnumSet.of(LOADING, CLOSED));
        TRANSITIONS.put(LOADING, EnumSet.of(LOADING, READY, PLAYING, ERROR, EMPTY, CLOSED));
        TRANSITIONS.put(READY, EnumSet.of(LOADING, PLAYING, SEEKING, STOPPING, EMPTY, CLOSED));
        TRANSITIONS.put(PLAYING, EnumSet.of(PAUSED, SEEKING, STOPPING, ENDED, ERROR, LOADING, EMPTY, CLOSED));
        TRANSITIONS.put(PAUSED, EnumSet.of(PLAYING, SEEKING, STOPPING, ERROR, LOADING, EMPTY, CLOSED));
        TRANSITIONS.put(SEEKING, EnumSet.of(SEEKING, PLAYING, PAUSED, READY, STOPPED, STOPPING, ERROR,
                LOADING, EMPTY, CLOSED));
        TRANSITIONS.put(STOPPING, EnumSet.of(STOPPED, PLAYING, ERROR, LOADING, EMPTY, CLOSED));
        TRANSITIONS.put(STOPPED, EnumSet.of(PLAYING, SEEKING, STOPPING, LOADING, EMPTY, CLOSED));
        TRANSITIONS.put(ENDED, EnumSet.of(SEEKING, STOPPING, LOADING, EMPTY, CLOSED));
        TRANSITIONS.put(ERROR, EnumSet.of(LOADING, STOPPED, EMPTY, CLOSED));
        TRANSITIONS.put(CLOSED, EnumSet.noneOf(PlaybackState.class));
    }

    /**
     * Returns whether moving from this state to {@code next} is a legal transition.
     *
     * @param next the state to move to
     * @return {@code true} if the transition is allowed
     */
    public boolean canTransitionTo(PlaybackState next) {
        return TRANSITIONS.get(this).contains(next);
    }

    /**
     * Returns whether a track is loaded and its decoder is open.
     *
     * @return {@code true} when a track is loaded
     */
    public boolean hasLoadedTrack() {
        return switch (this) {
            case READY, PLAYING, PAUSED, SEEKING, STOPPING, STOPPED, ENDED -> true;
            default -> false;
        };
    }

    /**
     * Returns whether the player has been closed.
     *
     * @return {@code true} for {@link #CLOSED}
     */
    public boolean isTerminal() {
        return this == CLOSED;
    }
}
