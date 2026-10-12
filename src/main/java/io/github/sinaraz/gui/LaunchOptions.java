package io.github.sinaraz.gui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command-line options for the GUI. */
record LaunchOptions(boolean simulatedAudio, boolean help, List<Path> files) {

    static final String USAGE = """
            usage: judio-gui [--null-audio] [file ...]
              --null-audio  use a simulated real-time device (no sound output)
              --help        show this help""";

    static LaunchOptions parse(String[] args) {
        boolean simulated = false;
        boolean help = false;
        List<Path> files = new ArrayList<>();
        for (String arg : args) {
            switch (arg) {
                case "--null-audio" -> simulated = true;
                case "--help", "-h" -> help = true;
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("unknown option: " + arg);
                    }
                    files.add(Path.of(arg));
                }
            }
        }
        return new LaunchOptions(simulated, help, List.copyOf(files));
    }
}
