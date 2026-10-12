package io.github.sinaraz.gui;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.model.ErrorKind;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackState;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LabelsTest {

    @Test
    void everyStateHasText() {
        for (PlaybackState state : PlaybackState.values()) {
            assertFalse(Labels.stateText(state).isBlank(), state.name());
        }
    }

    @Test
    void everyErrorKindHasText() {
        for (ErrorKind kind : ErrorKind.values()) {
            assertFalse(Labels.kindText(kind).isBlank(), kind.name());
        }
    }

    @Test
    void errorTextCombinesKindAndMessage() {
        PlaybackError error = new PlaybackError(ErrorKind.MISSING_FILE, "load", "no such file",
                null, Path.of("x.mp3"), null);
        assertEquals("File not found: no such file", Labels.errorText(error));
        PlaybackError bare = new PlaybackError(ErrorKind.INTERNAL, "load", null, null, null, null);
        assertEquals("Internal error", Labels.errorText(bare));
    }
}
