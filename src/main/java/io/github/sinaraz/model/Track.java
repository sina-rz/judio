package io.github.sinaraz.model;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable description of one playable item in the queue: a local file or a remote stream.
 *
 * <p>Identity is the {@link #id()}; two tracks for the same file added twice are distinct
 * queue entries. Updates produce new instances via the {@code with...} methods, which the
 * queue applies atomically.
 *
 * @param id             unique queue-entry id (random UUID)
 * @param location       where the audio comes from
 * @param title          title from tags, or {@code null} if unknown
 * @param artist         artist from tags, or {@code null}
 * @param album          album from tags, or {@code null}
 * @param albumArtist    album artist from tags, or {@code null}
 * @param genre          genre from tags, or {@code null}
 * @param year           release year, or {@code null}
 * @param trackNumber    track number on the album, or {@code null}
 * @param discNumber     disc number, or {@code null}
 * @param duration       length, or {@code null} until the file is opened; a stream may stay {@code null}
 * @param sampleRate     sample rate in hertz, or {@code null} until opened
 * @param channels       channel count, or {@code null} until opened
 * @param codec          decoder or codec name, or {@code null} until opened
 * @param fileSize       file size in bytes, or {@code -1} when unknown (always for streams)
 * @param hasCoverArt    whether the file has an embedded picture
 * @param metadataStatus whether tags have been read, or why they could not be
 * @param errorMessage   the most recent error for this entry, or {@code null}
 */
public record Track(
        String id,
        MediaLocation location,
        String title,
        String artist,
        String album,
        String albumArtist,
        String genre,
        Integer year,
        Integer trackNumber,
        Integer discNumber,
        Duration duration,
        Integer sampleRate,
        Integer channels,
        String codec,
        long fileSize,
        boolean hasCoverArt,
        TrackMetadataStatus metadataStatus,
        String errorMessage) {

    /** Checks the required fields. */
    public Track {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(metadataStatus, "metadataStatus");
    }

    /**
     * Creates a queue entry from a file path, with a file-name based title and no tags yet.
     *
     * @param file the audio file
     * @return a new queue entry
     */
    public static Track fromPath(Path file) {
        return fromLocation(MediaLocation.of(Objects.requireNonNull(file, "file")));
    }

    /**
     * Creates a queue entry for an {@code http} or {@code https} stream. Tags are not read from
     * streams, so the status is {@link TrackMetadataStatus#NOT_APPLICABLE}.
     *
     * @param uri the stream's address
     * @return a new queue entry
     * @throws IllegalArgumentException if the URI is not an absolute http or https address with a host
     */
    public static Track fromUri(URI uri) {
        return fromLocation(MediaLocation.of(uri));
    }

    /**
     * Creates a queue entry for any location.
     *
     * @param location where the audio comes from
     * @return a new queue entry
     */
    public static Track fromLocation(MediaLocation location) {
        Objects.requireNonNull(location, "location");
        long size = -1L;
        TrackMetadataStatus status = TrackMetadataStatus.NOT_APPLICABLE;
        if (location instanceof MediaLocation.LocalFile local) {
            status = TrackMetadataStatus.PENDING;
            try {
                size = Files.size(local.path());
            } catch (IOException ignored) {
                // Missing or unreadable files are reported when the track is opened.
            }
        }
        return new Track(
                UUID.randomUUID().toString(),
                location,
                stripExtension(location.displayName()),
                null, null, null, null, null, null, null,
                null, null, null, null,
                size,
                false,
                status,
                null);
    }

    /**
     * Applies tag values. Null fields in {@code metadata} keep the existing value.
     *
     * @param metadata the tags read from the file
     * @return an updated copy
     */
    public Track withMetadata(TrackMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        return new Track(id, location,
                orElse(metadata.title(), title),
                orElse(metadata.artist(), artist),
                orElse(metadata.album(), album),
                orElse(metadata.albumArtist(), albumArtist),
                orElse(metadata.genre(), genre),
                orElse(metadata.year(), year),
                orElse(metadata.trackNumber(), trackNumber),
                orElse(metadata.discNumber(), discNumber),
                duration, sampleRate, channels, codec, fileSize,
                metadata.hasCoverArt(),
                TrackMetadataStatus.LOADED,
                null);
    }

    /**
     * Records why the tags could not be read. The track stays playable.
     *
     * @param message the reason, shown to the user
     * @return an updated copy
     */
    public Track withMetadataFailure(String message) {
        return new Track(id, location, title, artist, album, albumArtist, genre, year, trackNumber, discNumber,
                duration, sampleRate, channels, codec, fileSize, hasCoverArt,
                TrackMetadataStatus.FAILED, message);
    }

    /**
     * Copies technical properties discovered when the decoder opened the source.
     *
     * @param info the stream description from the decoder
     * @return an updated copy
     */
    public Track withAudioInfo(AudioInfo info) {
        Objects.requireNonNull(info, "info");
        return new Track(id, location, title, artist, album, albumArtist, genre, year, trackNumber, discNumber,
                info.duration(), info.sampleRate(), info.channels(), info.sourceFormat(), fileSize,
                hasCoverArt, metadataStatus, errorMessage);
    }

    /**
     * Returns a copy with the given error message.
     *
     * @param message the error, or {@code null} to clear it
     * @return an updated copy
     */
    public Track withErrorMessage(String message) {
        return new Track(id, location, title, artist, album, albumArtist, genre, year, trackNumber, discNumber,
                duration, sampleRate, channels, codec, fileSize, hasCoverArt, metadataStatus, message);
    }

    /**
     * Whether this entry plays from an {@code http} or {@code https} stream.
     *
     * @return {@code true} for a stream
     */
    public boolean isRemote() {
        return location instanceof MediaLocation.Remote;
    }

    /**
     * Human-readable label such as {@code "Artist - Title"}, falling back to the file or URL name.
     *
     * @return the label
     */
    public String displayName() {
        if (title == null || title.isBlank()) {
            return location.displayName();
        }
        return (artist == null || artist.isBlank()) ? title : artist + " - " + title;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static <T> T orElse(T candidate, T fallback) {
        return candidate != null ? candidate : fallback;
    }
}
