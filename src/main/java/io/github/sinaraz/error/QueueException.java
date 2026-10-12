package io.github.sinaraz.error;

/** A queue operation was invalid, for example an out-of-range index or an empty queue. */
public class QueueException extends AudioPlayerException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param operation the queue operation that failed, such as {@code "add"}
     * @param message   why it failed
     */
    public QueueException(String operation, String message) {
        super(operation, message, null, null);
    }
}
