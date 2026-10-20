package io.github.sinaraz.error;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Base unchecked exception for every failure raised by the player.
 *
 * <p>Each exception names the {@linkplain #operation() operation} that failed, the
 * optional source file involved, and a cause when one is safe to expose. Remote-source exceptions
 * redact URL credentials and signed query data from messages and diagnostic causes. Messages are meant for humans;
 * code should branch on the exception type or on {@code model.ErrorKind} rather than parsing messages.
 */
public class AudioPlayerException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The operation that failed, for example {@code "open"}. */
    private final String operation;
    private final transient Path source;

    /**
     * Creates an exception for a failed public operation.
     *
     * @param operation the failed operation
     * @param message   human-readable description
     * @param source    the file involved, or {@code null}
     * @param cause     underlying exception, or {@code null}
     */
    public AudioPlayerException(String operation, String message, Path source, Throwable cause) {
        super(message, cause);
        this.operation = Objects.requireNonNull(operation, "operation");
        this.source = source;
    }

    /**
     * The public operation that failed, for example {@code "load"} or {@code "seek"}.
     *
     * @return the operation name
     */
    public String operation() {
        return operation;
    }

    /**
     * The source file involved, or {@code null} when the failure is not file-specific.
     *
     * @return the file, or {@code null}
     */
    public Path source() {
        return source;
    }
}
