package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.Track;

/** Step 4: react to events, and read tags and cover art. */
public final class Step4_Events {

    private Step4_Events() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("usage: Step4_Events <audio file>");
            return;
        }
        Path file = Path.of(args[0]);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        AtomicInteger progressUpdates = new AtomicInteger();

        try (JudioPlayer player = JudioPlayer.create()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackLoaded(Track track, AudioInfo info) {
                    System.out.printf("Loaded %s by %s: %d Hz, %d channel(s), %s%n",
                            Objects.toString(track.title(), track.displayName()),
                            Objects.toString(track.artist(), "unknown artist"),
                            info.sampleRate(), info.channels(), info.duration());
                }

                @Override
                public void onProgress(PlaybackSnapshot snapshot) {
                    progressUpdates.incrementAndGet();
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    finished.complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    finished.completeExceptionally(new IllegalStateException(error.message()));
                }
            });

            player.addFiles(file);
            Optional<CoverArt> cover = player.coverArt(player.queue().get(0));
            System.out.println(cover.map(coverArt -> "Cover art: " + coverArt.mimeType() + ", " + coverArt.data().length + " bytes").orElse("No cover art"));

            player.play();
            finished.get();   // rethrows the playback error, if there was one
            System.out.println("Progress updates received: " + progressUpdates.get());
        }
    }
}
