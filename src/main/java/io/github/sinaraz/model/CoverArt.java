package io.github.sinaraz.model;

import java.util.Arrays;
import java.util.Objects;

/**
 * Embedded cover image bytes (typically JPEG or PNG).
 *
 * @param mimeType MIME type reported by the tag, e.g. {@code image/jpeg}; may be empty
 * @param data     raw image bytes; the record keeps a private copy
 */
public record CoverArt(String mimeType, byte[] data) {

    /** Validates the components and copies the image bytes. */
    public CoverArt {
        Objects.requireNonNull(mimeType, "mimeType");
        data = Objects.requireNonNull(data, "data").clone();
    }

    /**
     * Returns a copy of the image bytes, so callers cannot change the record.
     *
     * @return a new array with the image bytes
     */
    @Override
    public byte[] data() {
        return data.clone();
    }

    /** Two covers are equal when their MIME types and image bytes are equal. */
    @Override
    public boolean equals(Object o) {
        return o instanceof CoverArt other
                && mimeType.equals(other.mimeType)
                && Arrays.equals(data, other.data);
    }

    @Override
    public int hashCode() {
        return 31 * mimeType.hashCode() + Arrays.hashCode(data);
    }

    @Override
    public String toString() {
        return "CoverArt[mimeType=" + mimeType + ", bytes=" + data.length + "]";
    }
}
