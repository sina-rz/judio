package io.github.sinaraz.gui;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.model.RepeatMode;

/**
 * Runs every player command on one background thread, in the order the user issued them. The
 * Swing event thread never blocks on the player: a pause can wait for the device to finish its
 * current write. Failures are passed to {@code errors}, which the frame shows on the event thread.
 */
final class FacadeActions implements PlayerActions, AutoCloseable {

    private static final long CLOSE_TIMEOUT_SECONDS = 5L;

    private final JudioPlayer player;
    private final Consumer<String> errors;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "gui-commands");
        thread.setDaemon(true);
        return thread;
    });

    FacadeActions(JudioPlayer player, Consumer<String> errors) {
        this.player = Objects.requireNonNull(player, "player");
        this.errors = Objects.requireNonNull(errors, "errors");
    }

    private void submit(String label, Runnable command) {
        try {
            executor.execute(() -> {
                try {
                    command.run();
                } catch (RuntimeException e) {
                    errors.accept(label + ": " + (e.getMessage() == null ? e.toString() : e.getMessage()));
                }
            });
        } catch (RejectedExecutionException closed) {
            // The player is shutting down; commands issued after close are ignored.
        }
    }

    @Override
    public void play() {
        submit("play", player::play);
    }

    @Override
    public void pause() {
        submit("pause", player::pause);
    }

    @Override
    public void resume() {
        submit("resume", player::resume);
    }

    @Override
    public void stop() {
        submit("stop", player::stop);
    }

    @Override
    public void next() {
        submit("next", player::next);
    }

    @Override
    public void previous() {
        submit("previous", player::previous);
    }

    @Override
    public void seek(Duration target) {
        submit("seek", () -> player.seek(target));
    }

    @Override
    public void seekRelative(Duration offset) {
        submit("seek", () -> player.seekRelative(offset));
    }

    @Override
    public void playAt(int index) {
        submit("play", () -> player.playAt(index));
    }

    @Override
    public void setVolume(double volume) {
        submit("volume", () -> player.setVolume(volume));
    }

    @Override
    public void setMuted(boolean muted) {
        submit("mute", () -> player.setMuted(muted));
    }

    @Override
    public void setPan(double pan) {
        submit("pan", () -> player.setPan(pan));
    }

    @Override
    public void setSpeed(double speed) {
        submit("speed", () -> player.setPlaybackSpeed(speed));
    }

    @Override
    public void setPreservePitch(boolean preserve) {
        submit("preserve pitch", () -> player.setPreservePitch(preserve));
    }

    @Override
    public void setRepeat(RepeatMode mode) {
        submit("repeat", () -> player.setRepeat(mode));
    }

    @Override
    public void setShuffle(boolean enabled) {
        submit("shuffle", () -> player.setShuffle(enabled));
    }

    @Override
    public void addFiles(List<Path> files) {
        List<Path> copy = List.copyOf(files);
        submit("add files", () -> player.addFiles(copy));
    }

    @Override
    public void addUrl(URI uri) {
        submit("add URL", () -> player.addUrl(uri));
    }

    @Override
    public void addDirectory(Path directory) {
        submit("add folder", () -> player.addDirectory(directory));
    }

    @Override
    public void removeAt(int index) {
        submit("remove", () -> player.remove(index));
    }

    @Override
    public void move(int from, int to) {
        submit("move", () -> player.move(from, to));
    }

    @Override
    public void clearQueue() {
        submit("clear", player::clear);
    }

    /** Closes the player after queued commands finish, waiting a bounded time. Idempotent. */
    @Override
    public void close() {
        try {
            executor.execute(player::close);
        } catch (RejectedExecutionException alreadyClosed) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
