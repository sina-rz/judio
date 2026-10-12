package io.github.sinaraz.internal.metadata;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!(track.location() instanceof MediaLocation.LocalFile(Path path))) {
                    return new Result(track.id(), null, null);
                }
                return new Result(track.id(), Id3Reader.read(path).metadata(), null);
            } catch (IOException | RuntimeException e) {
                String reason = e.getMessage() != null ? e.getMessage() : e.toString();
                return new Result(track.id(), null, "metadata unavailable: " + reason);
            }
        }, executor);
    }

    /** Synchronously reads the embedded cover image, if any. */
    public Optional<CoverArt> loadCoverArt(Path file) throws IOException {
        return Id3Reader.readCoverArt(file);
    }

    @Override
    public void close() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
