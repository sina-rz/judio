package io.github.sinaraz.internal.decoder;

import java.io.IOException;
import java.io.InputStream;

/**
 * Opens a new copy of the same stream bytes starting at a byte offset. Stream decoders use it to
 * seek, by reconnecting at the byte position that matches the target time.
 */
@FunctionalInterface
public interface RangeOpener {

    /**
     * Opens the stream from {@code offset} to the end of the resource.
     *
     * @param offset the byte position of the first byte to read, at least zero
     * @return a stream that starts at {@code offset}; the caller closes it
     * @throws IOException if the stream cannot be opened
     */
    InputStream open(long offset) throws IOException;
}
