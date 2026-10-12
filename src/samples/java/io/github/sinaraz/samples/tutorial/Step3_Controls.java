package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.time.Duration;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.RepeatMode;

/** Step 3: pause, seek, change volume and speed, and read the snapshot. */
public final class Step3_Controls {

    private Step3_Controls() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 1) {
            System.out.println("usage: Step3_Controls <audio file longer than 8 seconds>");
            return;
        }
        try (JudioPlayer player = JudioPlayer.create()) {
            player.addFiles(Path.of(args[0]));
            player.setVolume(0.8);
            player.setRepeat(RepeatMode.ALL);
            player.play();
            Thread.sleep(1_000);

            player.pause();
            print("paused", player.snapshot());

            player.resume();
            player.seek(Duration.ofSeconds(5));
            player.setPlaybackSpeed(1.25);
            Thread.sleep(1_000);
            print("after seek and speed", player.snapshot());

            player.setMuted(true);
            player.stop();
            print("stopped and muted", player.snapshot());
        }
    }

    private static void print(String label, PlaybackSnapshot s) {
        System.out.printf("%-20s state=%-8s position=%.1fs volume=%.2f muted=%b speed=%.2f%n",
                label, s.state(), s.position().toMillis() / 1000.0, s.volume(), s.muted(), s.speed());
    }
}
