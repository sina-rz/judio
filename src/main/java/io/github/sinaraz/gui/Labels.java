package io.github.sinaraz.gui;

import io.github.sinaraz.model.ErrorKind;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackState;

/** Human-readable text for states and errors. */
final class Labels {

    private Labels() {}

    static String stateText(PlaybackState state) {
        return switch (state) {
            case EMPTY -> "No track loaded";
            case LOADING -> "Loading...";
            case READY -> "Ready";
            case PLAYING -> "Playing";
            case PAUSED -> "Paused";
            case SEEKING -> "Seeking...";
            case STOPPING -> "Stopping...";
            case STOPPED -> "Stopped";
            case ENDED -> "Finished";
            case ERROR -> "Error";
            case CLOSED -> "Closed";
        };
    }

    static String kindText(ErrorKind kind) {
        return switch (kind) {
            case UNSUPPORTED_FORMAT -> "Unsupported format";
            case MISSING_FILE -> "File not found";
            case PERMISSION_DENIED -> "Permission denied";
            case CORRUPT_FILE -> "Corrupt file";
            case DECODER_FAILURE -> "Decoder failure";
            case AUDIO_DEVICE -> "Audio device";
            case INVALID_STATE -> "Invalid state";
            case INVALID_ARGUMENT -> "Invalid argument";
            case SEEK_NOT_SUPPORTED -> "Seeking not supported";
            case IO_ERROR -> "I/O error";
            case INTERNAL -> "Internal error";
        };
    }

    static String errorText(PlaybackError error) {
        String message = error.message() == null ? "" : error.message();
        return kindText(error.kind()) + (message.isEmpty() ? "" : ": " + message);
    }
}
