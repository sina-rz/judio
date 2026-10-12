package io.github.sinaraz.audio;

import java.io.IOException;
import java.nio.file.Path;

import io.github.sinaraz.model.MediaLocation;

/**
 * Opens a decoder for a track's location. The engine depends on this interface rather than on the
 * concrete {@link DecoderFactory}, so tests can supply synthetic decoders, and applications can add
 * formats or sources.
 */
@FunctionalInterface
public interface DecoderProvider {

    /**
     * Opens a decoder for {@code location}.
     *
     * @param location a local file or an http/https stream
     * @return an open decoder; the caller must close it
     * @throws IOException if the source cannot be read or its format is not supported
     */
    AudioDecoder open(MediaLocation location) throws IOException;

    /**
     * Convenience form for a local file.
     *
     * @param file audio file to open
     * @return an open decoder; the caller must close it
     * @throws IOException if the file cannot be read or its format is not supported
     */
    default AudioDecoder open(Path file) throws IOException {
        return open(MediaLocation.of(file));
    }
}
