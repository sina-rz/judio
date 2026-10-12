package io.github.sinaraz.error;

import io.github.sinaraz.model.PlaybackState;

/** An operation was requested that is not valid in the current {@link PlaybackState}. */
public class PlaybackStateException extends AudioPlayerException {

    private static final long serialVersionUID = 1L;

    private final transient PlaybackState state;

    /**
     * Creates the exception.
     *
     * @param operation the operation that was rejected, such as {@code "pause"}
     * @param state     the state the player was in
     * @param message   why the operation cannot run in that state
     */
    public PlaybackStateException(String operation, PlaybackState state, String message) {
        super(operation, message + " (state=" + state + ")", null, null);
        this.state = state;
    }

    /**
     * Returns the state the player was in when the operation was rejected.
     *
     * @return the state at the time of the failed operation
     */
    public PlaybackState state() {
        return state;
    }
}
