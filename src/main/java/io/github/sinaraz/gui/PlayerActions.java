package io.github.sinaraz.gui;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import io.github.sinaraz.model.RepeatMode;

/**
 * Everything the panels can ask the player to do. The Swing code depends on this interface only,
 * so panels can be tested without an audio device.
 */
interface PlayerActions {

    void play();

    void pause();

    void resume();

    void stop();

    void next();

    void previous();

    void seek(Duration target);

    void seekRelative(Duration offset);

    void playAt(int index);

    void setVolume(double volume);

    void setMuted(boolean muted);

    void setPan(double pan);

    void setSpeed(double speed);

    /**
     * Keeps the pitch when the speed changes, using time-stretching. The default does nothing so
     * that existing implementations keep compiling.
     */
    default void setPreservePitch(boolean preserve) {
    }

    void setRepeat(RepeatMode mode);

    void setShuffle(boolean enabled);

    void addFiles(List<Path> files);

    void addDirectory(Path directory);

    /** Adds an http or https stream. The default does nothing so existing implementations keep compiling. */
    default void addUrl(URI uri) {
    }

    void removeAt(int index);

    void move(int from, int to);

    void clearQueue();
}
