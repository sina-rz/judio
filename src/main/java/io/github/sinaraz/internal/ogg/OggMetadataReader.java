package io.github.sinaraz.internal.ogg;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.TrackMetadata;

/** Content-based reader for Vorbis comments in local Ogg Opus/Vorbis files. */
public final class OggMetadataReader {


    /** Reads the first supported Ogg audio stream's comment header without scanning its audio payload. */
    public static TrackMetadata read(Path path) throws IOException {
        return OggFileProbe.readMetadata(path);
    }

    /** Reads a supported embedded Vorbis-comment cover image, if present. */
    public static Optional<CoverArt> readCoverArt(Path path) throws IOException {
        return OggFileProbe.readCoverArt(path);
    }
}
