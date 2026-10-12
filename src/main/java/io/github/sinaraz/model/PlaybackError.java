package io.github.sinaraz.model;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import io.github.sinaraz.error.AudioDeviceException;
import io.github.sinaraz.error.AudioPlayerException;
import io.github.sinaraz.error.CorruptFileException;
import io.github.sinaraz.error.DecoderException;
import io.github.sinaraz.error.PlaybackStateException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;

/**
 * An error reported to listeners. Immutable and safe to keep after playback has moved on.
 *
 * @param kind      coarse classification
 * @param operation the operation during which the failure happened
 * @param message   human-readable description
 * @param track     the track involved, or {@code null}
 * @param source    the file involved, or {@code null}
 * @param cause     the original throwable, or {@code null}
 */
public record PlaybackError(
        ErrorKind kind,
        String operation,
        String message,
        Track track,
        Path source,
        Throwable cause) {

    /**
     * Builds an error from any throwable, classifying it by type.
     *
     * @param operation the operation during which the failure happened
     * @param track     the track involved, or {@code null}
     * @param failure   the throwable that was thrown
     * @return the error describing the failure
     */
    public static PlaybackError from(String operation, Track track, Throwable failure) {
        Path source = track != null && track.location() instanceof MediaLocation.LocalFile local
                ? local.path() : null;
        if (failure instanceof AudioPlayerException ape && ape.source() != null) {
            source = ape.source();
        }
        String message = failure.getMessage() != null ? failure.getMessage() : failure.toString();
        return new PlaybackError(classify(failure), operation, message, track, source, failure);
    }

    static ErrorKind classify(Throwable t) {
        if (t instanceof UnsupportedAudioFormatException) {
            return ErrorKind.UNSUPPORTED_FORMAT;
        }
        if (t instanceof CorruptFileException) {
            return ErrorKind.CORRUPT_FILE;
        }
        if (t instanceof DecoderException) {
            return ErrorKind.DECODER_FAILURE;
        }
        if (t instanceof SeekNotSupportedException) {
            return ErrorKind.SEEK_NOT_SUPPORTED;
        }
        if (t instanceof PlaybackStateException) {
            return ErrorKind.INVALID_STATE;
        }
        if (t instanceof AudioDeviceException) {
            return ErrorKind.AUDIO_DEVICE;
        }
        if (t instanceof NoSuchFileException || t instanceof FileNotFoundException) {
            return ErrorKind.MISSING_FILE;
        }
        if (t instanceof AccessDeniedException) {
            return ErrorKind.PERMISSION_DENIED;
        }
        if (t instanceof IllegalArgumentException) {
            return ErrorKind.INVALID_ARGUMENT;
        }
        if (t instanceof IOException) {
            return ErrorKind.IO_ERROR;
        }
        return ErrorKind.INTERNAL;
    }
}
