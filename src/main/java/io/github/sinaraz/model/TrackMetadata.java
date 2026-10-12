package io.github.sinaraz.model;

/**
 * Tag values read from a file. Any field may be {@code null} when the tag is absent.
 *
 * @param hasCoverArt whether an embedded picture frame exists
 * @param title       song title, or {@code null}
 * @param artist      artist, or {@code null}
 * @param album       album, or {@code null}
 * @param albumArtist album artist, or {@code null}
 * @param genre       genre, or {@code null}
 * @param year        release year, or {@code null}
 * @param trackNumber track number, or {@code null}
 * @param discNumber  disc number, or {@code null}
 */
public record TrackMetadata(
        String title,
        String artist,
        String album,
        String albumArtist,
        String genre,
        Integer year,
        Integer trackNumber,
        Integer discNumber,
        boolean hasCoverArt) {

    public static final TrackMetadata EMPTY =
            new TrackMetadata(null, null, null, null, null, null, null, null, false);
}
