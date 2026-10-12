package io.github.sinaraz.model;

import java.net.URI;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Where a track's audio comes from: a file on this computer, or an {@code http} or {@code https}
 * URL.
 *
 * <p>Only these two kinds exist, so code that handles tracks can use an exhaustive {@code switch}.
 * Other URI schemes such as {@code file:} and {@code jar:} are rejected: local files must be added
 * with a {@link Path} so that the player never reads a URI it did not expect.
 */
public sealed interface MediaLocation permits MediaLocation.LocalFile, MediaLocation.Remote {

    /**
     * A short label for the location: the file name, or the last segment of a URL path.
     *
     * @return a human-readable name, never empty
     */
    String displayName();

    /**
     * Creates a location for a local file.
     *
     * @param path the file; relative paths are resolved against the working directory
     * @return the location
     */
    static MediaLocation of(Path path) {
        return new LocalFile(path);
    }

    /**
     * Creates a location for a remote stream.
     *
     * @param uri an absolute {@code http} or {@code https} URI with a host
     * @return the location
     * @throws IllegalArgumentException if the scheme is not {@code http} or {@code https}, or the
     *                                  URI has no host
     */
    static MediaLocation of(URI uri) {
        return new Remote(uri);
    }

    /**
     * A file on this computer.
     *
     * @param path the absolute, normalised path of the file
     */
    record LocalFile(Path path) implements MediaLocation {

        /** Checks the path and makes it absolute and normalised. */
        public LocalFile {
            Objects.requireNonNull(path, "path");
            path = path.toAbsolutePath().normalize();
        }

        @Override
        public String displayName() {
            Path name = path.getFileName();
            return name == null ? path.toString() : name.toString();
        }

        @Override
        public String toString() {
            return path.toString();
        }
    }

    /**
     * An {@code http} or {@code https} stream.
     *
     * @param uri the stream's address, as given by the caller
     */
    record Remote(URI uri) implements MediaLocation {

        /** Checks that the address is absolute. */
        public Remote {
            Objects.requireNonNull(uri, "uri");
            if (!uri.isAbsolute()) {
                throw new IllegalArgumentException("stream address must be absolute: " + uri);
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IllegalArgumentException("only http and https streams are supported: " + uri);
            }
            if (uri.getHost() == null) {
                throw new IllegalArgumentException("stream address has no host: " + uri);
            }
        }

        @Override
        public String displayName() {
            String path = uri.getPath();
            if (path != null) {
                String trimmed = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
                int slash = trimmed.lastIndexOf('/');
                String last = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
                if (!last.isEmpty()) {
                    return last;
                }
            }
            return uri.getHost();
        }

        @Override
        public String toString() {
            return uri.toString();
        }
    }
}
