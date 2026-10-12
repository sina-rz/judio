package io.github.sinaraz.samples;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.Track;

/**
 * Plays an audio stream over HTTP or HTTPS. With an address argument it plays that URL from the start.
 * Without one, it streams a public MP3 and seeks to its last 13 seconds, so the sample finishes quickly
 * and shows that seeking works on a stream.
 */
public final class StreamOverHttp {

    static final URI DEFAULT_SONG = URI.create(
            "https://file-examples.com/storage/fe9ac44c8a6acc7d7989e28/2017/11/file_example_MP3_5MG.mp3");


    static void main(String[] args) throws Exception {
        URI uri = args.length > 0 ? URI.create(args[0]) : DEFAULT_SONG;

        CompletableFuture<AudioInfo> loaded = new CompletableFuture<>();
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try (JudioPlayer player = JudioPlayer.builder().simulatedOutput().build()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackLoaded(Track track, AudioInfo info) {
                    loaded.complete(info);
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    System.out.println("Stream finished.");
                    finished.complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    IllegalStateException failure = new IllegalStateException(error.message());
                    loaded.completeExceptionally(failure);
                    finished.completeExceptionally(failure);
                }
            });

            Track track = player.addUrl(uri);
            System.out.println("Queued " + track.displayName() + " from " + uri);
            player.play();
            if (args.length == 0) {
                loaded.get(60, TimeUnit.SECONDS);
                player.seek(Duration.ofSeconds(120));
            }
            finished.get(60, TimeUnit.SECONDS);
        }
    }
}
