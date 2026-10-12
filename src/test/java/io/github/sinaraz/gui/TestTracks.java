package io.github.sinaraz.gui;

import java.nio.file.Path;
import java.time.Duration;

import io.github.sinaraz.model.Track;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.TrackMetadataStatus;

/** Builds track fixtures without touching the file system. */
final class TestTracks {

    private TestTracks() {}

    static Track track(String name, String title, String artist, Duration duration) {
        return new Track("id-" + name, MediaLocation.of(Path.of("/music/" + name + ".mp3")), title, artist, "Album",
                null, null, null, null, null, duration, 44100, 2, "MPEG-1 Layer III",
                1024L, false, TrackMetadataStatus.LOADED, null);
    }
}
