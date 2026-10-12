package io.github.sinaraz.error;

import java.nio.file.Path;

/** A decoder failed while opening, reading or seeking a supported file. */
public class DecoderException extends AudioPlayerException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for a decoder failure.
     *
     * @param operation the failed operation, such as {@code "open"} or {@code "seek"}
     * @param message   human-readable description
     * @param source    the file being decoded, or {@code null}
     * @param cause     underlying exception, or {@code null}
     */
    public DecoderException(String operation, String message, Path source, Throwable cause) {
        super(operation, message, source, cause);
    }
}
