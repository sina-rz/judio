package io.github.sinaraz.samples;

import java.nio.file.Files;
import java.nio.file.Path;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.model.PlaybackSnapshot;

/** Changes the speed with and without pitch preservation, so the difference can be heard. */
public final class PitchPreservingSpeed {


    static void main() throws Exception {
        Path dir = Files.createTempDirectory("pitch-preserving");
        Path file = SampleSupport.writeToneWav(dir, "tone.wav", 6.0);

        try (JudioPlayer player = JudioPlayer.builder().simulatedOutput().build()) {
            player.addFiles(file);
            player.setPreservePitch(true);
            player.setPlaybackSpeed(0.75);
            player.play();
            sleep(500);
            show("0.75x, pitch kept (WSOLA)", player.snapshot());

            player.setPlaybackSpeed(1.5);
            sleep(500);
            show("1.5x, pitch kept (WSOLA)", player.snapshot());

            player.setPreservePitch(false);
            sleep(300);
            show("1.5x, pitch follows speed (resampling)", player.snapshot());
            player.stop();
        }
    }

    private static void show(String label, PlaybackSnapshot s) {
        System.out.printf("%-42s state=%-8s position=%5.2f s speed=%.2f preserve=%s%n", label, s.state(),
                s.position().toMillis() / 1000.0, s.speed(), s.preservePitch());
    }

    private static void sleep(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
