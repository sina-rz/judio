package io.github.sinaraz.cli;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.error.AudioPlayerException;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

/**
 * Line-oriented console front end.
 *
 * <p>Input is read on the calling thread and blocks only that thread. Playback runs on the audio
 * worker and events arrive on the event thread, so typing a command never interrupts audio.
 * Indices are 0-based and match what {@code queue} prints.
 */
public final class ConsoleApp {

    static final String HELP = """
            Commands (indices are 0-based, as shown by 'queue'):
              help                       show this help
              load <path>                replace the queue with one file and prepare it (no playback)
              add <path>                 append a file to the queue
              add-dir <directory>        append every audio file in a directory
              add-url <http(s) address>  append an audio stream to the queue
              queue                      list the queue
              remove <index>             remove an entry
              move <from> <to>           reorder the queue
              clear                      stop and empty the queue
              play                       start (or continue) playback
              pause | resume | stop      transport control
              next | previous            move through the queue
              seek <seconds>             seek to an absolute position
              forward <seconds>          seek forward
              back <seconds>             seek backward
              volume <0.0-1.0>           set volume
              mute                       toggle mute
              speed <0.5-2.0>            set playback speed (changes pitch unless preserve-pitch is on)
              preserve-pitch on|off      keep the pitch when the speed changes
              pan <-1.0..1.0>            set stereo pan (-1 left, 0 centre, 1 right)
              repeat off|one|all         set repeat mode
              shuffle on|off             toggle shuffle
              status                     show the current state
              quit                       exit
            Paths containing spaces are fine: everything after the command is the path.
            """;

    private final JudioPlayer player;
    private final PrintStream out;
    private final BufferedReader in;
    private final AtomicLong lastProgressSecond = new AtomicLong(-1L);

    /**
     * Creates a console front end. The streams are not closed by this class.
     *
     * @param player the facade to control
     * @param out    where output is written
     * @param in     where commands are read
     */
    public ConsoleApp(JudioPlayer player, PrintStream out, BufferedReader in) {
        this.player = player;
        this.out = out;
        this.in = in;
    }

    /**
     * Runs the read-eval loop until {@code quit} or end of input.
     *
     * @param initialFiles files to queue before the first prompt; may be empty
     * @throws IOException if reading the input fails
     */
    public void run(List<Path> initialFiles) throws IOException {
        player.addListener(new ConsoleListener());
        out.println("judio. Type 'help' for commands.");
        if (!initialFiles.isEmpty()) {
            execute("add-files-initial", () -> player.addFiles(initialFiles));
        }
        while (true) {
            out.print("> ");
            out.flush();
            String line = in.readLine();
            if (line == null) {
                out.println();
                out.println("End of input; shutting down.");
                return;
            }
            if (line.isBlank()) {
                continue;
            }
            if (!dispatch(line.strip())) {
                return;
            }
        }
    }

    /** Executes one command. Returns {@code false} when the application should exit. */
    boolean dispatch(String line) {
        int space = line.indexOf(' ');
        String command = (space < 0 ? line : line.substring(0, space)).toLowerCase(Locale.ROOT);
        String argument = space < 0 ? "" : line.substring(space + 1).strip();
        switch (command) {
            case "help" -> out.print(HELP);
            case "quit", "exit" -> {
                out.println("Shutting down...");
                return false;
            }
            case "load" -> execute(command, () -> {
                requireArgument(command, argument);
                Path target = toPath(argument);
                // Validate first: a bad path must not destroy the current queue.
                if (!Files.isRegularFile(target)) {
                    throw new IllegalArgumentException("not a file: " + target);
                }
                player.clear();
                List<Track> added = player.addFiles(List.of(target));
                player.prepare(0);
                out.println("Loaded: " + added.getFirst().location());
            });
            case "add" -> execute(command, () -> {
                requireArgument(command, argument);
                List<Track> added = player.addFiles(List.of(toPath(argument)));
                out.println("Added: " + added.getFirst().location());
            });
            case "add-dir" -> execute(command, () -> {
                requireArgument(command, argument);
                List<Track> added = player.addDirectory(toPath(argument));
                out.println("Added " + added.size() + " file(s).");
            });
            case "add-url" -> execute(command, () -> {
                requireArgument(command, argument);
                player.addUrl(URI.create(argument));
                out.println("Added stream: " + argument);
            });
            case "queue" -> printQueue();
            case "remove" -> execute(command, () -> {
                int index = parseIndex(command, argument);
                var result = player.remove(index);
                out.println("Removed: " + result.removed().displayName());
                if (result.currentRemoved()) {
                    out.println("Playback stopped because the current track was removed.");
                }
            });
            case "move" -> execute(command, () -> {
                String[] parts = argument.split("\\s+");
                if (parts.length != 2) {
                    throw new IllegalArgumentException("usage: move <from> <to>");
                }
                player.move(parseInt(command, parts[0]), parseInt(command, parts[1]));
                printQueue();
            });
            case "clear" -> execute(command, () -> {
                player.clear();
                out.println("Queue cleared.");
            });
            case "play" -> execute(command, player::play);
            case "pause" -> execute(command, player::pause);
            case "resume" -> execute(command, player::resume);
            case "stop" -> execute(command, player::stop);
            case "next" -> execute(command, () -> {
                if (!player.next()) {
                    out.println("End of queue.");
                }
            });
            case "previous", "prev" -> execute(command, player::previous);
            case "seek" -> execute(command, () -> player.seek(seconds(command, argument)));
            case "forward" -> execute(command, () -> player.seekRelative(seconds(command, argument)));
            case "back" -> execute(command, () -> player.seekRelative(seconds(command, argument).negated()));
            case "volume" -> execute(command, () -> {
                double v = parseDouble(command, argument);
                if (v < 0.0 || v > 1.0) {
                    throw new IllegalArgumentException("volume must be between 0.0 and 1.0");
                }
                player.setVolume(v);
                out.printf(Locale.ROOT, "Volume: %.2f%n", v);
            });
            case "mute" -> execute(command, () -> {
                boolean muted = !player.snapshot().muted();
                player.setMuted(muted);
                out.println(muted ? "Muted." : "Unmuted.");
            });
            case "speed" -> execute(command, () -> {
                double s = parseDouble(command, argument);
                player.setPlaybackSpeed(s);
                out.printf(Locale.ROOT, "Speed: %.2fx (requested %.2f)%n", player.snapshot().speed(), s);
            });
            case "preserve-pitch" -> execute(command, () -> {
                requireArgument(command, argument);
                boolean on = switch (argument.toLowerCase(Locale.ROOT)) {
                    case "on" -> true;
                    case "off" -> false;
                    default -> throw new IllegalArgumentException("usage: preserve-pitch on|off");
                };
                player.setPreservePitch(on);
                out.println("Preserve pitch: " + (on ? "on" : "off"));
            });
            case "pan" -> execute(command, () -> {
                double p = parseDouble(command, argument);
                player.setPan(p);
                out.printf(Locale.ROOT, "Pan: %.2f%n", player.snapshot().pan());
            });
            case "repeat" -> execute(command, () -> {
                requireArgument(command, argument);
                RepeatMode mode = RepeatMode.parse(argument);
                player.setRepeat(mode);
                out.println("Repeat: " + mode.name().toLowerCase(Locale.ROOT));
            });
            case "shuffle" -> execute(command, () -> {
                requireArgument(command, argument);
                boolean on = switch (argument.toLowerCase(Locale.ROOT)) {
                    case "on" -> true;
                    case "off" -> false;
                    default -> throw new IllegalArgumentException("usage: shuffle on|off");
                };
                player.setShuffle(on);
                out.println("Shuffle: " + (on ? "on" : "off"));
            });
            case "status" -> printStatus(player.snapshot());
            default -> out.println("Unknown command '" + command + "'. Type 'help'.");
        }
        return true;
    }

    // ------------------------------------------------------------- helpers

    @FunctionalInterface
    private interface Action {
        void run() throws Exception;
    }

    private void execute(String command, Action action) {
        try {
            action.run();
        } catch (AudioPlayerException | IllegalArgumentException e) {
            out.println("error [" + command + "]: " + e.getMessage());
        } catch (Exception e) {
            out.println("error [" + command + "]: " + e);
        }
    }

    private static void requireArgument(String command, String argument) {
        if (argument.isEmpty()) {
            throw new IllegalArgumentException("'" + command + "' needs an argument; see 'help'");
        }
    }

    private static Path toPath(String argument) {
        String text = argument;
        if (text.length() >= 2 && (text.startsWith("\"") && text.endsWith("\"")
                || text.startsWith("'") && text.endsWith("'"))) {
            text = text.substring(1, text.length() - 1);
        }
        return Path.of(text);
    }

    private static int parseIndex(String command, String argument) {
        requireArgument(command, argument);
        return parseInt(command, argument);
    }

    private static int parseInt(String command, String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a whole number (" + command + ")");
        }
    }

    private static double parseDouble(String command, String text) {
        requireArgument(command, text);
        try {
            double v = Double.parseDouble(text.strip());
            if (Double.isNaN(v) || Double.isInfinite(v)) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + text + "' is not a number (" + command + ")");
        }
    }

    private static Duration seconds(String command, String text) {
        double v = parseDouble(command, text);
        if (v < 0.0) {
            throw new IllegalArgumentException("seconds must not be negative");
        }
        return Duration.ofMillis(Math.round(v * 1000.0));
    }

    private void printQueue() {
        List<Track> items = player.queue();
        QueueSnapshot info = player.queueSnapshot();
        if (items.isEmpty()) {
            out.println("Queue is empty.");
            return;
        }
        out.printf(Locale.ROOT, "Queue (%d entries, repeat=%s, shuffle=%s):%n",
                items.size(), info.repeat().name().toLowerCase(Locale.ROOT), info.shuffle() ? "on" : "off");
        for (int i = 0; i < items.size(); i++) {
            Track t = items.get(i);
            String marker = i == info.currentIndex() ? ">" : " ";
            String length = t.duration() != null ? formatTime(t.duration()) : "--:--";
            String status = t.errorMessage() != null ? "  [error: " + t.errorMessage() + "]" : "";
            out.printf(Locale.ROOT, "%s %3d  %s  %s%s%n", marker, i, length, t.displayName(), status);
        }
    }

    private void printStatus(PlaybackSnapshot s) {
        out.println("State:    " + s.state());
        out.println("Track:    " + (s.track() == null ? "(none)" : s.track().displayName()));
        out.println("Position: " + formatTime(s.position()) + " / " + formatTime(s.duration()));
        out.printf(Locale.ROOT, "Volume:   %.2f%s   Speed: %.2fx%s   Pan: %.2f%n",
                s.volume(), s.muted() ? " (muted)" : "", s.speed(),
                s.preservePitch() ? " (pitch kept)" : "", s.pan());
        out.printf(Locale.ROOT, "Queue:    %d of %d   Repeat: %s   Shuffle: %s%n",
                s.queueIndex() + 1, s.queueSize(), s.repeat().name().toLowerCase(Locale.ROOT),
                s.shuffle() ? "on" : "off");
        if (s.error() != null) {
            out.println("Error:    " + s.error().message());
        }
    }

    static String formatTime(Duration d) {
        long total = Math.max(0L, d.toSeconds());
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    /** Prints state transitions and occasional progress. Runs on the event thread. */
    private final class ConsoleListener implements PlaybackListener {

        private PlaybackState lastState;

        @Override
        public void onStateChanged(PlaybackSnapshot s) {
            if (s.state() != lastState) {
                lastState = s.state();
                if (s.state() == PlaybackState.PLAYING || s.state() == PlaybackState.PAUSED
                        || s.state() == PlaybackState.STOPPED || s.state() == PlaybackState.ENDED) {
                    out.println();
                    out.println("[" + s.state().name().toLowerCase(Locale.ROOT) + "]"
                            + (s.track() != null ? " " + s.track().displayName() : ""));
                    out.print("> ");
                    out.flush();
                }
            }
        }

        @Override
        public void onTrackLoaded(Track track, AudioInfo info) {
            out.println();
            out.printf(Locale.ROOT, "[loaded] %s (%s, %d Hz, %d ch, %s)%n",
                    track.displayName(), formatTime(info.duration()), info.sampleRate(), info.channels(),
                    info.decoderName());
            out.print("> ");
            out.flush();
        }

        @Override
        public void onProgress(PlaybackSnapshot s) {
            long second = s.position().toSeconds();
            if (second % 5 == 0 && lastProgressSecond.getAndSet(second) != second) {
                out.println();
                out.printf(Locale.ROOT, "[progress] %s / %s%n", formatTime(s.position()), formatTime(s.duration()));
                out.print("> ");
                out.flush();
            }
        }

        @Override
        public void onPlaybackCompleted(Track track) {
            out.println();
            out.println("[finished] " + track.displayName());
            out.print("> ");
            out.flush();
        }

        @Override
        public void onError(PlaybackError error) {
            out.println();
            out.println("[error] " + error.kind() + " during " + error.operation() + ": " + error.message());
            out.print("> ");
            out.flush();
        }

        @Override
        public void onQueueChanged(List<Track> queue, QueueSnapshot state) {
            // Queue edits are echoed by the commands themselves; avoid duplicate output here.
        }
    }
}
