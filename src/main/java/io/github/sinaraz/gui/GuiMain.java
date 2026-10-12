package io.github.sinaraz.gui;

import java.awt.GraphicsEnvironment;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatDarkLaf;

import io.github.sinaraz.JudioPlayer;

/** Entry point for the Swing player: {@code ./gradlew runGui}. */
public final class GuiMain {

    private GuiMain() {}

    /**
     * Starts the Swing player. Options are parsed by {@link LaunchOptions}.
     *
     * @param args command-line options and files to queue
     */
    public static void main(String[] args) {
        LaunchOptions options;
        try {
            options = LaunchOptions.parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println(e.getMessage());
            System.err.println(LaunchOptions.USAGE);
            System.exit(2);
            return;
        }
        if (options.help()) {
            System.out.println(LaunchOptions.USAGE);
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("No display is available, so the GUI cannot start. "
                    + "Use the console player instead: ./gradlew run");
            System.exit(1);
            return;
        }

        installLookAndFeel();
        JudioPlayer.Builder builder = JudioPlayer.builder();
        if (options.simulatedAudio()) {
            builder.simulatedOutput();
        }
        JudioPlayer player = builder.build();
        Runtime.getRuntime().addShutdownHook(new Thread(player::close, "player-shutdown"));

        SwingUtilities.invokeLater(() ->
                new PlayerFrame(player, options.simulatedAudio(), options.files()).setVisible(true));
    }

    /** Installs the FlatLaf dark theme. Must run before any Swing component is created. */
    private static void installLookAndFeel() {
        FlatDarkLaf.setup();
    }
}
