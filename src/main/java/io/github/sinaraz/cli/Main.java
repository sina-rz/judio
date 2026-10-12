package io.github.sinaraz.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.sinaraz.JudioPlayer;

/**
 * Entry point.
 *
 * <pre>
 *   ./gradlew run                                   # interactive, system audio
 *   ./gradlew run --args="--null-audio song.mp3"    # no sound card needed; simulated real-time device
 * </pre>
 */
public final class Main {
    /**
     * Starts the command-line player.
     *
     * @param args optional audio files to queue, plus {@code --null-audio} for no sound output
     * @throws Exception if the player cannot start
     */
    static void main(String[] args) throws Exception {
        PrintStream out = System.out;
        boolean simulated = false;
        List<Path> initial = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "--null-audio" -> simulated = true;
                case "--help", "-h" -> {
                    out.println("usage: judio [--null-audio] [file ...]");
                    out.println("  --null-audio  play through a simulated real-time device (no sound output)");
                    return;
                }
                default -> initial.add(Path.of(arg));
            }
        }

        JudioPlayer.Builder builder = JudioPlayer.builder();
        if (simulated) {
            builder.simulatedOutput();
        }
        JudioPlayer player = builder.build();
        Thread hook = new Thread(() -> {
            System.err.println();
            System.err.println("Interrupted: closing audio output...");
            player.close();
        }, "player-shutdown");
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            new ConsoleApp(player, out, new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)))
                    .run(initial);
        } finally {
            player.close();
            try {
                Runtime.getRuntime().removeShutdownHook(hook); // the hook is only for signals
            } catch (IllegalStateException alreadyShuttingDown) {
                // the JVM is exiting; the hook is already running
            }
        }
        out.println("Goodbye.");
    }
}
