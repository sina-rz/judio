package io.github.sinaraz.internal.decoder;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Makes a network-fed stream behave like a file: a read returns the requested number of bytes
 * unless the stream ends first, and a return of {@code -1} means the real end of the stream.
 *
 * <p>Decoders built on {@link javax.sound.sampled.AudioSystem} can return 0 when a short read
 * leaves less than one whole frame, and {@code available()} gives them no reliable way to wait.
 * Without this wrapper a momentary gap in the network would look like the end of the audio.
 * The wrapper blocks inside {@link #read(byte[], int, int)} instead, so callers never see a
 * spurious end of stream and never spin.
 */
final class FullReadInputStream extends FilterInputStream {

    FullReadInputStream(InputStream in) {
        super(Objects.requireNonNull(in, "in"));
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        int total = 0;
        while (total < length) {
            int n = in.read(buffer, offset + total, length - total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total == 0 && length > 0 ? -1 : total;
    }

    /** Reports nothing buffered, so buffering wrappers always go through {@link #read(byte[], int, int)}. */
    @Override
    public int available() {
        return 0;
    }
}
