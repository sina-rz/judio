package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

/** Step 5: custom configuration, a simulated device, and a repeatable shuffle order. */
public final class Step5_CustomPlayer {

    private Step5_CustomPlayer() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: Step5_CustomPlayer <audio file> [more files]");
            return;
        }
        PlaybackConfig config = PlaybackConfig.builder()
                .progressInterval(Duration.ofMillis(250))
                .previousRestartThreshold(Duration.ofSeconds(2))
                .build();
        // One future per queued track. Each completes on success or failure, so one bad file does not
        // stop the wait for the others.
        Map<String, CompletableFuture<Void>> outcomes = new ConcurrentHashMap<>();

        try (JudioPlayer player = JudioPlayer.builder()
                .config(config)
                .simulatedOutput()
                .random(new Random(42))
                .build()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackChanged(Track previous, Track current) {
                    System.out.println("Now playing: " + (current == null ? "nothing" : current.displayName()));
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    outcomes.get(track.id()).complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    System.out.println("Error: " + error.message());
                    if (error.track() != null) {
                        outcomes.get(error.track().id()).complete(null);
                    }
                }
            });

            List<Track> tracks = player.addFiles(Arrays.stream(args).map(Path::of).toList());
            for (Track track : tracks) {
                outcomes.put(track.id(), new CompletableFuture<>());
            }
            player.setRepeat(RepeatMode.OFF);
            player.setShuffle(true);
            player.play();
            // Waits for every track; the timeout scales with the number of files.
            CompletableFuture.allOf(outcomes.values().toArray(new CompletableFuture<?>[0]))
                    .get(60L * tracks.size(), TimeUnit.SECONDS);
        }
    }
}
