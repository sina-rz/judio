package io.github.sinaraz.internal.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sinaraz.internal.decoder.FormatSniffer;
import io.github.sinaraz.internal.lifecycle.CloseDeadline;
import io.github.sinaraz.internal.ogg.OggMetadataReader;
import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.Track;
import io.github.sinaraz.model.TrackMetadata;

/**
 * Reads tags on a small background pool so large files never delay the audio worker or the UI.
 * Failures are returned as results, never thrown: a broken tag must not prevent playback.
 */
public final class MetadataService implements AutoCloseable {

    /**
     * Outcome of one asynchronous tag read.
     *
     * @param trackId  the track the result belongs to
     * @param metadata tag values, or {@code null} if reading failed
     * @param error    a human-readable failure reason, or {@code null} on success
     */
    public record Result(String trackId, TrackMetadata metadata, String error) {}

    private final ExecutorService executor;

    // Single-entry cover-art cache keyed by (path, size, last-modified): the GUI
    // asks for the current track's cover on every load, and re-parsing a multi-MB
    // tag each time is pure waste. One entry bounds memory to one image.
    private record CoverKey(Path path, long size, long lastModified) {}
    private CoverKey lastCoverKey;
    private Optional<CoverArt> lastCover;   // guarded by this

    public MetadataService() {
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread t = new Thread(runnable, "metadata-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    public CompletableFuture<Result> loadAsync(Track track) {
        Objects.requireNonNull(track, "track");
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!(track.location() instanceof MediaLocation.LocalFile(Path path))) {
                        return new Result(track.id(), null, null);
                    }
                    TrackMetadata metadata = FormatSniffer.detect(path) == FormatSniffer.Format.OGG
                            ? OggMetadataReader.read(path)
                            : Id3Reader.read(path).metadata();
                    return new Result(track.id(), metadata, null);
                } catch (IOException | RuntimeException e) {
                    String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                    return new Result(track.id(), null, "metadata unavailable: " + reason);
                }
            }, executor);
        } catch (RejectedExecutionException closedPool) {
            // Closing raced with a submission: report a failed read, never leak
            // the executor's internal exception type through the public API.
            return CompletableFuture.completedFuture(
                    new Result(track.id(), null, "metadata unavailable: player is closed"));
        }
    }

    /** Synchronously reads the embedded cover image, if any (cached per unchanged file). */
    public synchronized Optional<CoverArt> loadCoverArt(Path file) throws IOException {
        CoverKey key = null;
        try {
            key = new CoverKey(file, java.nio.file.Files.size(file),
                    java.nio.file.Files.getLastModifiedTime(file).toMillis());
        } catch (IOException ignored) {
            // Fall through and let the read below produce the real error.
        }
        if (key != null && key.equals(lastCoverKey)) {
            return lastCover;
        }
        Optional<CoverArt> art = FormatSniffer.detect(file) == FormatSniffer.Format.OGG
                ? OggMetadataReader.readCoverArt(file)
                : Id3Reader.readCoverArt(file);
        if (key != null) {
            lastCoverKey = key;
            lastCover = art;
        }
        return art;
    }

    @Override
    public void close() {
        close(CloseDeadline.after(Duration.ofSeconds(1)));
    }

    /** Stops metadata work and waits only for the time remaining on a shared player-close deadline. */
    public void close(CloseDeadline deadline) {
        Objects.requireNonNull(deadline, "deadline");
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(deadline.remainingNanos(), TimeUnit.NANOSECONDS)) {
                System.getLogger(MetadataService.class.getName()).log(System.Logger.Level.WARNING,
                        "metadata workers did not stop before the close deadline");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
