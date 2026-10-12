package io.github.sinaraz.gui;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.audio.SimulatedAudioOutput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the real main window and checks its structure. Skipped when the JVM is headless (no
 * display), so it runs on desktops and in CI with a virtual display, and is skipped elsewhere.
 */
class PlayerFrameTest {

    @Test
    void mainWindowBuildsWithMenusAndSimulatedOutputLabel() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(), "no display: window test skipped");

        PlaybackConfig config = PlaybackConfig.defaults();
        JudioPlayer player = JudioPlayer.builder().config(config).simulatedOutput().random(new Random(1L)).build();
        AtomicReference<JFrame> frameRef = new AtomicReference<>();
        try {
            SwingUtilities.invokeAndWait(() -> frameRef.set(new PlayerFrame(player, true, List.of())));
            JFrame frame = frameRef.get();
            try {
                assertEquals("judio", frame.getTitle());
                JMenuBar bar = frame.getJMenuBar();
                assertNotNull(bar, "menu bar");
                List<String> menus = new java.util.ArrayList<>();
                for (int i = 0; i < bar.getMenuCount(); i++) {
                    JMenu menu = bar.getMenu(i);
                    menus.add(menu.getText());
                }
                assertTrue(menus.containsAll(List.of("File", "Playback", "Help")), "menus: " + menus);
                assertTrue(frame.getWidth() >= frame.getMinimumSize().width, "window is at least its minimum width");
                assertTrue(containsText(frame, "Output: simulated real-time device"),
                        "status line names the simulated device");
            } finally {
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        } finally {
            player.close();
        }
    }

    private static boolean containsText(java.awt.Container container, String text) {
        for (java.awt.Component c : container.getComponents()) {
            if (c instanceof javax.swing.JLabel label && text.equals(label.getText())) {
                return true;
            }
            if (c instanceof java.awt.Container child && containsText(child, text)) {
                return true;
            }
        }
        return false;
    }
}
