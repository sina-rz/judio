package io.github.sinaraz.error;

/**
 * The operating system audio device could not be opened, written to, or controlled.
 */
public class AudioDeviceException extends AudioPlayerException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception for a failed audio device operation.
     *
     * @param operation the failed operation, such as {@code "open"} or {@code "write"}
     * @param message   human-readable description
     * @param cause     underlying exception, or {@code null}
     */
    public AudioDeviceException(String operation, String message, Throwable cause) {
        super(operation, message, null, cause);
    }
}
