package io.github.sinaraz.audio;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;

/**
 * Settings for {@code http} and {@code https} streams.
 *
 * <p>The defaults suit most internet radio and podcast servers. Change them when a server is slow,
 * needs authentication, or uses a private certificate authority.
 *
 * @param connectTimeout how long to wait for the server to answer a request
 * @param readTimeout    how long a stream may go without delivering data before it fails
 * @param bufferBytes    how much audio is fetched ahead of the decoder, which absorbs network jitter
 * @param maxRetries     how many times a dropped connection is resumed with an HTTP range request
 * @param headers        extra request headers, such as {@code Authorization}; copied on construction
 * @param sslContext     TLS settings for {@code https}, or {@code null} for the JDK default trust store
 */
public record StreamOptions(
        Duration connectTimeout,
        Duration readTimeout,
        int bufferBytes,
        int maxRetries,
        Map<String, String> headers,
        SSLContext sslContext) {

    /** Smallest allowed read-ahead buffer, 32 KiB. */
    public static final int MIN_BUFFER_BYTES = 32 * 1024;

    /** Checks each value and rejects the impossible ones. */
    public StreamOptions {
        Objects.requireNonNull(connectTimeout, "connectTimeout");
        Objects.requireNonNull(readTimeout, "readTimeout");
        if (connectTimeout.isNegative() || connectTimeout.isZero()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("readTimeout must be positive");
        }
        if (bufferBytes < MIN_BUFFER_BYTES) {
            throw new IllegalArgumentException("bufferBytes must be at least " + MIN_BUFFER_BYTES);
        }
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(headers, "headers")));
    }

    /**
     * The default options: 10 s connect timeout, 15 s read timeout, 256 KiB read-ahead, three
     * resume attempts, no extra headers, and the JDK's default TLS settings.
     *
     * @return the default options
     */
    public static StreamOptions defaults() {
        return builder().build();
    }

    /**
     * Returns a builder that starts from the defaults.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /** Builds a {@link StreamOptions}. Unset fields keep their defaults. */
    public static final class Builder {

        private Duration connectTimeout = Duration.ofSeconds(10);
        private Duration readTimeout = Duration.ofSeconds(15);
        private int bufferBytes = 256 * 1024;
        private int maxRetries = 3;
        private final Map<String, String> headers = new LinkedHashMap<>();
        private SSLContext sslContext;

        private Builder() {}

        /**
         * Sets how long to wait for the server to answer.
         *
         * @param value how long to wait for the server to answer
         * @return this builder
         */
        public Builder connectTimeout(Duration value) {
            this.connectTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets how long a stream may stall before it fails.
         *
         * @param value how long a stream may stall before it fails
         * @return this builder
         */
        public Builder readTimeout(Duration value) {
            this.readTimeout = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Sets the read-ahead buffer size.
         *
         * @param value read-ahead size in bytes, at least {@link #MIN_BUFFER_BYTES}
         * @return this builder
         */
        public Builder bufferBytes(int value) {
            this.bufferBytes = value;
            return this;
        }

        /**
         * Sets how many dropped connections may be resumed.
         *
         * @param value how many dropped connections may be resumed; zero disables resuming
         * @return this builder
         */
        public Builder maxRetries(int value) {
            this.maxRetries = value;
            return this;
        }

        /**
         * Adds a request header sent with every stream request.
         *
         * @param name  header name, such as {@code Authorization}
         * @param value header value
         * @return this builder
         */
        public Builder header(String name, String value) {
            headers.put(Objects.requireNonNull(name, "name"), Objects.requireNonNull(value, "value"));
            return this;
        }

        /**
         * Sets the TLS settings used for {@code https} streams.
         *
         * @param value TLS settings for {@code https}, for example a context that trusts a private CA
         * @return this builder
         */
        public Builder sslContext(SSLContext value) {
            this.sslContext = Objects.requireNonNull(value, "value");
            return this;
        }

        /**
         * Validates the values and creates the options.
         *
         * @return the options
         * @throws IllegalArgumentException if a value is out of range
         */
        public StreamOptions build() {
            return new StreamOptions(connectTimeout, readTimeout, bufferBytes, maxRetries, headers, sslContext);
        }
    }
}
