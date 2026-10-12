package io.github.sinaraz.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackStateTest {

    @Test
    void acceptsLegalTransitions() {
        assertTrue(PlaybackState.EMPTY.canTransitionTo(PlaybackState.LOADING));
        assertTrue(PlaybackState.LOADING.canTransitionTo(PlaybackState.READY));
        assertTrue(PlaybackState.READY.canTransitionTo(PlaybackState.PLAYING));
        assertTrue(PlaybackState.PLAYING.canTransitionTo(PlaybackState.PAUSED));
        assertTrue(PlaybackState.PAUSED.canTransitionTo(PlaybackState.PLAYING));
        assertTrue(PlaybackState.PLAYING.canTransitionTo(PlaybackState.SEEKING));
        assertTrue(PlaybackState.SEEKING.canTransitionTo(PlaybackState.PLAYING));
        assertTrue(PlaybackState.PLAYING.canTransitionTo(PlaybackState.ENDED));
        assertTrue(PlaybackState.PLAYING.canTransitionTo(PlaybackState.STOPPING));
        assertTrue(PlaybackState.STOPPING.canTransitionTo(PlaybackState.STOPPED));
        assertTrue(PlaybackState.ENDED.canTransitionTo(PlaybackState.SEEKING));
        assertTrue(PlaybackState.ERROR.canTransitionTo(PlaybackState.LOADING));
    }

    @Test
    void rejectsIllegalTransitions() {
        assertFalse(PlaybackState.EMPTY.canTransitionTo(PlaybackState.PLAYING));
        assertFalse(PlaybackState.EMPTY.canTransitionTo(PlaybackState.PAUSED));
        assertFalse(PlaybackState.EMPTY.canTransitionTo(PlaybackState.SEEKING));
        assertFalse(PlaybackState.ENDED.canTransitionTo(PlaybackState.PAUSED));
        assertFalse(PlaybackState.STOPPED.canTransitionTo(PlaybackState.PAUSED));
        assertFalse(PlaybackState.PAUSED.canTransitionTo(PlaybackState.ENDED));
    }

    @Test
    void closedIsTerminalAndReachableFromEveryOtherState() {
        for (PlaybackState from : PlaybackState.values()) {
            if (from == PlaybackState.CLOSED) {
                for (PlaybackState to : PlaybackState.values()) {
                    assertFalse(from.canTransitionTo(to), "CLOSED must not transition to " + to);
                }
            } else {
                assertTrue(from.canTransitionTo(PlaybackState.CLOSED), from + " must be closable");
            }
        }
        assertTrue(PlaybackState.CLOSED.isTerminal());
    }

    @Test
    void loadedTrackStatesAreMarked() {
        assertTrue(PlaybackState.PLAYING.hasLoadedTrack());
        assertTrue(PlaybackState.PAUSED.hasLoadedTrack());
        assertFalse(PlaybackState.EMPTY.hasLoadedTrack());
        assertFalse(PlaybackState.LOADING.hasLoadedTrack());
        assertFalse(PlaybackState.CLOSED.hasLoadedTrack());
    }
}
