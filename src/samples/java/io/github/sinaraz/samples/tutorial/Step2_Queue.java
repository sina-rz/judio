package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.model.Track;

/** Step 2: build a queue, play it, and move between entries. */
public final class Step2_Queue {

    private Step2_Queue() {}

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.out.println("usage: Step2_Queue <file> <file> [more files]");
            return;
        }
        try (JudioPlayer player = JudioPlayer.create()) {
            List<Path> files = new ArrayList<>();
            for (String arg : args) {
                files.add(Path.of(arg));
            }
            player.addFiles(files);

            List<Track> queue = player.queue();
            for (int i = 0; i < queue.size(); i++) {
                System.out.println(i + ": " + queue.get(i).displayName());
            }

            player.play();
            Thread.sleep(3_000);

            player.next();
            Thread.sleep(3_000);
            System.out.println("Now at index " + player.queueSnapshot().currentIndex());

            player.previous();
            Thread.sleep(1_000);
            System.out.println("Now at index " + player.queueSnapshot().currentIndex());
        }
    }
}
