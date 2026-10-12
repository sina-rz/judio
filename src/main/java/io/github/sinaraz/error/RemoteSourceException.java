package io.github.sinaraz.error;

import java.net.URI;
import java.util.Objects;

/**
 * A remote stream could not be opened or read: the server refused the request, the connection
 * failed and could not be resumed, or the server stopped sending data.
 *
 * <p>{@link #statusCode()} is the HTTP status when the server answered, and {@code -1} when no
 * response was received. Retries have already been attempted when this is thrown.
 */
public class RemoteSourceException extends AudioPlayerException {

    private static final long serialVersionUID = 1L;

    private final transient URI uri;
    /** The HTTP status, or {@code -1} when there was no response. */
    private final int statusCode;

    /**
     * Creates the exception.
     *
     * @param operation  the failed operation, such as {@code "open"} or {@code "read"}
     * @param message    human-readable description
     * @param uri        the stream's address
     * @param statusCode the HTTP status code, or {@code -1} when there was no response
     * @param cause      the underlying exception, or {@code null}
     */
    public RemoteSourceException(String operation, String message, URI uri, int statusCode, Throwable cause) {
        super(operation, message, null, cause);
        this.uri = Objects.requireNonNull(uri, "uri");
        this.statusCode = statusCode;
    }

    /**
     * The stream's address.
     *
     * @return the URI that failed
     */
    public URI uri() {
        return uri;
    }

    /**
     * The HTTP status code the server sent, or {@code -1} when no response was received.
     *
     * @return the status code
     */
    public int statusCode() {
        return statusCode;
    }
}
