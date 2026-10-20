package io.github.sinaraz.error;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/**
 * A remote stream could not be opened or read: the server refused the request, the connection
 * failed and could not be resumed, or the server stopped sending data.
 *
 * <p>{@link #statusCode()} is the HTTP status when the server answered, and {@code -1} when no
 * response was received. Retries have already been attempted when this is thrown. URL user-info,
 * query strings, and fragments are omitted from the message and {@link #uri()}; cause diagnostics
 * are sanitized when they contain those secret components.
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
     * @param uri        the stream's address; credentials, query, and fragment are not retained
     * @param statusCode the HTTP status code, or {@code -1} when there was no response
     * @param cause      the underlying exception, or {@code null}; diagnostics containing URL secret
     *                   components are replaced with a type-only cause
     */
    public RemoteSourceException(String operation, String message, URI uri, int statusCode, Throwable cause) {
        super(operation, safeMessage(message, Objects.requireNonNull(uri, "uri")), null, safeCause(cause, uri));
        this.uri = redactedUri(uri);
        this.statusCode = statusCode;
    }

    /**
     * The failed stream address with user-info, query, and fragment removed. Signed URL credentials
     * are deliberately not retained by the exception.
     *
     * @return a redacted URI that identifies the stream path
     */
    public URI uri() {
        return uri;
    }

    private static URI redactedUri(URI uri) {
        try {
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null);
        } catch (URISyntaxException | NullPointerException e) {
            return URI.create("remote://redacted");
        }
    }

    private static String safeMessage(String message, URI uri) {
        if (!diagnosticExposesUrlSecrets(message, uri)) {
            return message;
        }
        return "remote request failed for " + redactedUri(uri);
    }

    /** Keeps an original cause unless its message or nested causes expose URI secret components. */
    private static Throwable safeCause(Throwable cause, URI uri) {
        if (cause == null || !causeExposesUrlSecrets(cause, uri)) {
            return cause;
        }
        java.io.IOException safe = new java.io.IOException(
                "remote I/O failure (" + cause.getClass().getSimpleName() + ")");
        safe.setStackTrace(cause.getStackTrace());
        return safe;
    }

    private static boolean causeExposesUrlSecrets(Throwable cause, URI uri) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        ArrayDeque<Throwable> pending = new ArrayDeque<>();
        pending.add(cause);
        while (!pending.isEmpty()) {
            Throwable current = pending.removeFirst();
            if (!seen.add(current)) {
                continue;
            }
            if (diagnosticExposesUrlSecrets(current.toString(), uri)) {
                return true;
            }
            if (current.getCause() != null) {
                pending.addLast(current.getCause());
            }
            Collections.addAll(pending, current.getSuppressed());
        }
        return false;
    }

    private static boolean diagnosticExposesUrlSecrets(String diagnostic, URI uri) {
        if (diagnostic == null) {
            return false;
        }
        String rawUserInfo = uri.getRawUserInfo();
        String userInfo = uri.getUserInfo();
        if (contains(diagnostic, rawUserInfo) || contains(diagnostic, userInfo)
                || contains(diagnostic, uri.getRawQuery()) || contains(diagnostic, uri.getRawFragment())
                || contains(diagnostic, uri.getFragment())) {
            return true;
        }
        for (String userInfoValue : new String[] {rawUserInfo, userInfo}) {
            if (userInfoValue != null) {
                for (String credential : userInfoValue.split(":")) {
                    if (contains(diagnostic, credential) || contains(diagnostic, decode(credential))) {
                        return true;
                    }
                }
            }
        }
        String query = uri.getRawQuery();
        if (query != null) {
            for (String parameter : query.split("&")) {
                int equals = parameter.indexOf('=');
                String value = equals < 0 ? parameter : parameter.substring(equals + 1);
                if (contains(diagnostic, value) || contains(diagnostic, decode(value))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(String text, String secret) {
        return secret != null && !secret.isEmpty() && text.contains(secret);
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException malformedEncoding) {
            return value;
        }
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
