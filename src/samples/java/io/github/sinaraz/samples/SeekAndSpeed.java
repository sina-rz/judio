package io.github.sinaraz.samples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.random.RandomGenerator;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.audio.SimulatedAudioOutput;
import io.github.sinaraz.model.PlaybackSnapshot;

/** Seeking, speed, volume, and pause/resume on a single track. */
public final class SeekAndSpeed {


    static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("seek-and-speed");
        Path file = args.length > 0 ? Path.of(args[0])
                : SampleSupport.writeToneWav(dir, "tone.wav", 6.0);

        try (JudioPlayer player = JudioPlayer.builder().simulatedOutput().build()) {

            player.addFiles(java.util.List.of(file));
            player.play();
            sleep(300);
            show("playing at 1.0x", player.snapshot());

            player.setPlaybackSpeed(1.5);           // changes pitch as well (linear resampling)
            player.setVolume(0.5);
            sleep(500);
            show("playing at 1.5x, volume 0.5", player.snapshot());

            player.pause();
            show("paused", player.snapshot());
            sleep(400);
            show("still paused after 400 ms (position does not move)", player.snapshot());

            player.resume();
            player.seek(Duration.ofSeconds(4));     // MP3 seeks are sample-exact, WAV/AIFF/AU are frame-exact
            sleep(200);
            show("after seeking to 4 s", player.snapshot());

            player.seekRelative(Duration.ofSeconds(-2));
            sleep(100);
            show("after seeking back 2 s", player.snapshot());
            player.stop();
        }
    }

    private static void show(String label, PlaybackSnapshot s) {
        System.out.printf("%-52s state=%-8s position=%5.2f s speed=%.2f volume=%.2f%n", label, s.state(),
                s.position().toMillis() / 1000.0, s.speed(), s.volume());
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
