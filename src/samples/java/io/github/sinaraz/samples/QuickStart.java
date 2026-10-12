package io.github.sinaraz.samples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.Track;

/**
 * The smallest complete use of the library: queue files, play them, and react to events.
 *
 * <p>Uses a simulated real-time output, so it runs without a sound card. Pass audio files as
 * arguments to play them instead of the generated tone.
 */
public final class QuickStart {


    static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("quickstart");
        List<Path> files = args.length > 0
                ? Stream.of(args).map(Path::of).toList()
                : List.of(SampleSupport.writeToneWav(dir, "tone.wav", 2.0));

        CompletableFuture<Void> finished = new CompletableFuture<>();
        AtomicInteger progressEvents = new AtomicInteger();

        try (JudioPlayer player = JudioPlayer.builder().simulatedOutput().build()) {

            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackLoaded(Track track, io.github.sinaraz.model.AudioInfo info) {
                    System.out.printf("loaded %s: %s, %d Hz, %d ch, %.2f s%n", track.displayName(),
                            info.decoderName(), info.sampleRate(), info.channels(),
                            info.duration().toMillis() / 1000.0);
                }

                @Override
                public void onProgress(PlaybackSnapshot snapshot) {
                    progressEvents.incrementAndGet();
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    System.out.println("completed " + track.displayName());
                    finished.complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    finished.completeExceptionally(new IllegalStateException(error.message()));
                }
            });

            player.addFiles(files);
            player.play();
            finished.get(60, TimeUnit.SECONDS);   // throws on playback error or after 60 s
            PlaybackSnapshot end = player.snapshot();
            System.out.printf("final state %s, position %.2f s, %d progress events%n",
                    end.state(), end.position().toMillis() / 1000.0, progressEvents.get());
        }
    }
}
