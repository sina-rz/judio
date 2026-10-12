package io.github.sinaraz.error;

import java.nio.file.Path;

/**
 * The current source cannot be repositioned. The player never fakes seeking: when a
 * decoder reports {@code isSeekable() == false} this exception is thrown instead.
 */
public class SeekNotSupportedException extends AudioPlayerException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param operation the operation that was attempted, for example {@code "seek"}
     * @param message   human-readable description
     * @param source    the file involved, or {@code null} for a stream
     */
    public SeekNotSupportedException(String operation, String message, Path source) {
        super(operation, message, source, null);
    }
}
