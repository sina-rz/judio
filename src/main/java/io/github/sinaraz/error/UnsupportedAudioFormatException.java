package io.github.sinaraz.error;

import java.nio.file.Path;

/** The file is readable but its format or sample layout is not supported by any decoder. */
public class UnsupportedAudioFormatException extends AudioPlayerException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param operation the operation that was attempted, for example {@code "open"}
     * @param message   human-readable description
     * @param source    the file involved, or {@code null}
     * @param cause     the underlying exception, or {@code null}
     */
    public UnsupportedAudioFormatException(String operation, String message, Path source, Throwable cause) {
        super(operation, message, source, cause);
    }
}
