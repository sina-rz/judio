package io.github.sinaraz.error;

import java.nio.file.Path;

/**
 * The file has a recognised signature but its content is structurally invalid
 * (for example: an MP3 with no decodable frames, or a truncated header).
 */
public class CorruptFileException extends DecoderException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for a damaged file.
     *
     * @param operation the failed operation, such as {@code "open"} or {@code "read"}
     * @param message   human-readable description
     * @param source    the damaged file, or {@code null}
     * @param cause     underlying exception, or {@code null}
     */
    public CorruptFileException(String operation, String message, Path source, Throwable cause) {
        super(operation, message, source, cause);
    }
}
