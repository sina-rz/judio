package io.github.sinaraz.internal.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import io.github.sinaraz.audio.StreamOptions;
import io.github.sinaraz.error.RemoteSourceException;

/**
 * A blocking byte stream over HTTP or HTTPS, with read-ahead and automatic resume.
 *
 * <ul>
 *   <li><b>Opening</b> is synchronous: the request is sent and the status is checked before the
 *       constructor returns, so a missing file or a refused connection fails at load time.</li>
 *   <li><b>Read-ahead:</b> a daemon thread fetches into a ring buffer of
 *       {@link StreamOptions#bufferBytes()}. Network jitter then does not reach the decoder unless
 *       the buffer runs dry.</li>
 *   <li><b>Resume:</b> if the connection drops, the thread reconnects with
 *       {@code Range: bytes=N-} where {@code N} is the number of bytes already received. This needs a
 *       server that answers range requests with {@code 206}. Without range support, the stream
 *       fails instead of replaying audio.</li>
 *   <li><b>Stalls:</b> if no data arrives for {@link StreamOptions#readTimeout()}, the read fails.</li>
 * </ul>
 *
 * <p>Thread-safety: one consumer thread calls {@link #read}. The fetch thread and {@link #close} use
 * the same monitor, which is never held during network I/O.
 */
public final class HttpByteStream extends InputStream {

    private static final int FETCH_CHUNK = 16 * 1024;
    private static final long BACKOFF_MILLIS = 250L;

    private final HttpClient client;
    private final URI uri;
    private final StreamOptions options;
    private final byte[] ring;
    private final Thread fetcher;

    private int head;            // index of the next byte to read from ring
    private int count;           // bytes available in ring
    private boolean eof;         // server finished the body
    private boolean closed;
    private RemoteSourceException failure;
    private long fetchedBytes;   // bytes received from the server, equal to the next range offset (fetch thread only)
    private int retriesLeft;     // resume attempts left for the current incident (fetch thread only)
    // Written by the fetch thread (and the constructor), read by decoder threads:
    private volatile boolean rangeSupported;
    private volatile long totalLength = -1L; // full resource size from the response headers, or -1 if unknown
    private InputStream body;    // guarded by this; closed from close() or on reconnect

    /**
     * Sends the first request and starts read-ahead.
     *
     * @param client  the shared client; it must follow redirects
     * @param uri     an absolute http or https address
     * @param options timeouts, buffer size, retry count, and headers
     * @throws RemoteSourceException if the server refuses the request or cannot be reached
     */
    public HttpByteStream(HttpClient client, URI uri, StreamOptions options) {
        this(client, uri, options, 0L);
    }

    /**
     * Sends a request for the resource starting at {@code startOffset} and starts read-ahead.
     *
     * <p>A non-zero offset needs a server that answers with {@code 206 Partial Content}. Other servers
     * fail here rather than returning the wrong bytes.
     *
     * @param client      the shared client; it must follow redirects
     * @param uri         an absolute http or https address
     * @param options     timeouts, buffer size, retry count, and headers
     * @param startOffset the byte position of the first byte to read, at least zero
     * @throws RemoteSourceException if the server refuses the request, cannot be reached, or ignores
     *                               the range request
     */
    public HttpByteStream(HttpClient client, URI uri, StreamOptions options, long startOffset) {
        this.client = Objects.requireNonNull(client, "client");
        this.uri = Objects.requireNonNull(uri, "uri");
        this.options = Objects.requireNonNull(options, "options");
        if (startOffset < 0) {
            throw new IllegalArgumentException("startOffset must not be negative: " + startOffset);
        }
        this.ring = new byte[options.bufferBytes()];
        this.retriesLeft = options.maxRetries();
        this.fetchedBytes = startOffset;
        this.body = connect(startOffset);
        this.fetcher = new Thread(this::fetchLoop, "judio-stream-fetch");
        this.fetcher.setDaemon(true);
        this.fetcher.start();
    }

    /** The redacted address, without user-info, query, or fragment. */
    public URI uri() {
        return URI.create(redacted(uri));
    }

    /**
     * The size of the whole resource in bytes, or {@code -1} if the server did not report it.
     *
     * <p>For a stream opened at an offset, this is still the size of the whole resource.
     */
    public long length() {
        return totalLength;
    }

    /** Whether the server answered range requests, so that seeking and resuming are possible. */
    public boolean supportsRanges() {
        return rangeSupported;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int n = read(one, 0, 1);
        return n < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public synchronized int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        long deadline = System.nanoTime() + options.readTimeout().toNanos();
        while (count == 0) {
            if (closed) {
                throw new IOException("stream is closed: " + redacted(uri));
            }
            if (failure != null) {
                throw failure;
            }
            if (eof) {
                return -1;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new RemoteSourceException("read",
                        "no data received for " + options.readTimeout() + " from " + redacted(uri), uri, -1, null);
            }
            try {
                TimeUnit.NANOSECONDS.timedWait(this, remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while waiting for " + redacted(uri));
            }
        }
        int n = Math.min(length, Math.min(count, ring.length - head));
        System.arraycopy(ring, head, buffer, offset, n);
        head = (head + n) % ring.length;
        count -= n;
        notifyAll();
        return n;
    }

    @Override
    public int available() {
        synchronized (this) {
            return count;
        }
    }

    @Override
    public void close() {
        InputStream toClose;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            toClose = body;
            body = null;
            notifyAll();
        }
        closeQuietly(toClose);
        Thread f = fetcher;
        if (f != null && f != Thread.currentThread()) {
            f.interrupt();
        }
    }

    // ------------------------------------------------------------------ fetch thread

    private void fetchLoop() {
        byte[] chunk = new byte[FETCH_CHUNK];
        try {
            while (true) {
                InputStream current;
                synchronized (this) {
                    if (closed) {
                        return;
                    }
                    current = body;
                }
                if (current == null) {
                    try {
                        current = reconnect();
                    } catch (RemoteSourceException reconnectFailure) {
                        if (isClosed()) {
                            return;
                        }
                        if (retriesLeft <= 0) {
                            fail(reconnectFailure);
                            return;
                        }
                        retriesLeft--;
                        if (!sleepBackoff()) {
                            return;
                        }
                        continue;
                    }
                    if (current == null) {
                        return; // closed while reconnecting
                    }
                }
                int n;
                try {
                    n = current.read(chunk);
                } catch (IOException e) {
                    if (isClosed()) {
                        return;
                    }
                    if (!canResume()) {
                        fail(new RemoteSourceException("read",
                                "connection lost and cannot be resumed: " + redacted(uri), uri, -1, e));
                        return;
                    }
                    retriesLeft--;
                    detachBody(current);
                    if (!sleepBackoff()) {
                        return;
                    }
                    continue;
                }
                if (n < 0) {
                    finish();
                    return;
                }
                fetchedBytes += n;
                // Data is flowing again: the resume budget is per incident,
                // not a lifetime allowance for the whole stream.
                retriesLeft = options.maxRetries();
                if (!publish(chunk, n)) {
                    return;
                }
            }
        } catch (RemoteSourceException e) {
            fail(e);
        } finally {
            InputStream last;
            synchronized (this) {
                last = body;
                body = null;
            }
            closeQuietly(last);
        }
    }

    /** Opens a new request at the current offset. Returns {@code null} if the stream was closed meanwhile. */
    private InputStream reconnect() {
        InputStream fresh = connect(fetchedBytes);
        synchronized (this) {
            if (closed) {
                closeQuietly(fresh);
                return null;
            }
            body = fresh;
        }
        return fresh;
    }

    private boolean canResume() {
        return retriesLeft > 0 && (fetchedBytes == 0L || rangeSupported);
    }

    private void detachBody(InputStream broken) {
        synchronized (this) {
            if (body == broken) {
                body = null;
            }
        }
        closeQuietly(broken);
    }

    private boolean sleepBackoff() {
        try {
            Thread.sleep(BACKOFF_MILLIS);
            return !isClosed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Copies bytes into the ring, waiting while it is full. Returns {@code false} if closed. */
    private boolean publish(byte[] src, int length) {
        int offset = 0;
        while (offset < length) {
            synchronized (this) {
                while (!closed && count == ring.length) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if (closed) {
                    return false;
                }
                int tail = (head + count) % ring.length;
                int n = Math.min(length - offset, Math.min(ring.length - count, ring.length - tail));
                System.arraycopy(src, offset, ring, tail, n);
                count += n;
                offset += n;
                notifyAll();
            }
        }
        return true;
    }

    private synchronized void finish() {
        eof = true;
        notifyAll();
    }

    private synchronized void fail(RemoteSourceException cause) {
        failure = cause;
        notifyAll();
    }

    private synchronized boolean isClosed() {
        return closed;
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * Sends a GET for {@code offset} onward and checks the response.
     *
     * @throws RemoteSourceException on a refused request, a non-success status, a server that ignores
     *                               the range request, or a connection failure
     */
    private InputStream connect(long offset) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                .timeout(options.connectTimeout())
                .GET();
        for (Map.Entry<String, String> header : options.headers().entrySet()) {
            request.header(header.getKey(), header.getValue());
        }
        if (offset > 0) {
            request.header("Range", "bytes=" + offset + "-");
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RemoteSourceException("open", "interrupted while connecting to " + redacted(uri), uri, -1, e);
        } catch (IOException | IllegalArgumentException e) {
            // Exception messages from HttpClient implementations are not safe to interpolate
            // into user-facing diagnostics. RemoteSourceException redacts URL secrets from its cause.
            throw new RemoteSourceException(offset == 0 ? "open" : "resume",
                    "cannot connect to " + redacted(uri) + ": " + e.getClass().getSimpleName(), uri, -1, e);
        }
        int status = response.statusCode();
        InputStream in = response.body();
        if (status == 206) {
            String contentRange = response.headers().firstValue("Content-Range").orElse(null);
            if (offset > 0 && rangeStartFromContentRange(contentRange) != offset) {
                closeQuietly(in);
                throw new RemoteSourceException("resume",
                        "server returned an invalid Content-Range for byte " + offset + ": " + redacted(uri),
                        uri, status, null);
            }
            rangeSupported = true;
            long rangedTotal = totalFromContentRange(contentRange);
            if (rangedTotal >= 0) {
                totalLength = rangedTotal;
            }
        } else if (status == 200) {
            if (offset > 0) {
                closeQuietly(in);
                throw new RemoteSourceException("resume",
                        "server ignored the range request; the stream cannot be resumed: " + redacted(uri), uri, status, null);
            }
            rangeSupported = response.headers().firstValue("Accept-Ranges")
                    .map(v -> v.equalsIgnoreCase("bytes")).orElse(false);
            totalLength = response.headers().firstValue("Content-Length")
                    .map(HttpByteStream::parseLengthOrUnknown).orElse(-1L);
        } else {
            closeQuietly(in);
            throw new RemoteSourceException(offset == 0 ? "open" : "resume",
                    "HTTP " + status + " from " + redacted(uri), uri, status, null);
        }
        return in;
    }

    /** Reads the start offset from {@code Content-Range: bytes first-last/total}, or returns -1. */
    private static long rangeStartFromContentRange(String value) {
        if (value == null) {
            return -1L;
        }
        String trimmed = value.trim();
        if (!trimmed.regionMatches(true, 0, "bytes ", 0, 6)) {
            return -1L;
        }
        int dash = trimmed.indexOf('-', 6);
        if (dash < 0) {
            return -1L;
        }
        return parseLengthOrUnknown(trimmed.substring(6, dash));
    }

    /** Reads the total from {@code Content-Range: bytes first-last/total}, or returns -1. */
    private static long totalFromContentRange(String value) {
        if (value == null) {
            return -1L;
        }
        int slash = value.lastIndexOf('/');
        if (slash < 0) {
            return -1L;
        }
        return parseLengthOrUnknown(value.substring(slash + 1).trim());
    }

    private static long parseLengthOrUnknown(String text) {
        try {
            long value = Long.parseLong(text.trim());
            return value >= 0 ? value : -1L;
        } catch (NumberFormatException e) {
            return -1L; // "*" means the total is unknown
        }
    }

    /**
     * The address with credentials, query and fragment removed, for use in error
     * messages and diagnostic accessors (signed stream URLs routinely carry secrets in the query string).
     * The full URI is retained only internally for the actual HTTP request.
     */
    static String redacted(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    uri.getPath(), null, null).toString();
        } catch (Exception e) {
            return uri.getScheme() + "://" + uri.getHost();
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // Closing a finished or broken connection has nothing useful to report.
            }
        }
    }
}
