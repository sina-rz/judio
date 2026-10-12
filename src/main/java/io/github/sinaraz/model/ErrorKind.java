package io.github.sinaraz.model;

/** Coarse classification of a failure, used for reporting and recovery policy. */
public enum ErrorKind {
    /** No installed decoder recognises the file's content. */
    UNSUPPORTED_FORMAT,
    /** The file does not exist. */
    MISSING_FILE,
    /** The file exists but cannot be read. */
    PERMISSION_DENIED,
    /** The content is recognised but damaged or inconsistent. */
    CORRUPT_FILE,
    /** A decoder failed while reading or seeking. */
    DECODER_FAILURE,
    /** The audio output could not be opened, written, or controlled. */
    AUDIO_DEVICE,
    /** The operation is not allowed in the current {@link PlaybackState}. */
    INVALID_STATE,
    /** An argument was out of range or otherwise invalid. */
    INVALID_ARGUMENT,
    /** The current source cannot be repositioned. */
    SEEK_NOT_SUPPORTED,
    /** A file read or write failed for another I/O reason. */
    IO_ERROR,
    /** An unexpected internal failure; please report it. */
    INTERNAL
}
