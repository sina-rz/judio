package io.github.sinaraz.gui;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JSlider;
import javax.swing.JTable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the real Swing components (found in the panel's component tree) and checks which
 * commands reach the {@link PlayerActions}. Snapshot updates must never send commands back.
 * These tests need no display.
 */
class PanelWiringTest {

    private RecordingActions actions;

    @BeforeEach
    void setUp() {
        actions = new RecordingActions();
    }

    private static PlaybackSnapshot snapshot(Track track, PlaybackState state, int queueSize) {
        return new PlaybackSnapshot(track, state, Duration.ZERO, Duration.ofSeconds(10),
                1.0, false, 1.0, 0.0, false, 0, queueSize, RepeatMode.OFF, false, null);
    }

    private static PlaybackSnapshot idle() {
        return new PlaybackSnapshot(null, PlaybackState.EMPTY, Duration.ZERO, Duration.ZERO,
                1.0, false, 1.0, 0.0, false, -1, 0, RepeatMode.OFF, false, null);
    }

    @Test
    void playPauseButtonFollowsState() {
        NowPlayingPanel panel = new NowPlayingPanel(actions);
        Track track = TestTracks.track("a", "A", "Artist", Duration.ofSeconds(10));

        panel.applySnapshot(snapshot(track, PlaybackState.READY, 1));
        button(panel, "Play").doClick();
        panel.applySnapshot(snapshot(track, PlaybackState.PLAYING, 1));
        button(panel, "Pause").doClick();
        panel.applySnapshot(snapshot(track, PlaybackState.PAUSED, 1));
        button(panel, "Play").doClick();

        assertEquals(List.of("play", "pause", "resume"), actions.calls);
    }

    @Test
    void transportButtonsSendTheirCommands() {
        NowPlayingPanel panel = new NowPlayingPanel(actions);
        Track track = TestTracks.track("a", "A", "Artist", Duration.ofSeconds(10));
        panel.applySnapshot(snapshot(track, PlaybackState.PLAYING, 2));

        button(panel, "Previous").doClick();
        button(panel, "Next").doClick();
        button(panel, "-5 s").doClick();
        button(panel, "+5 s").doClick();
        button(panel, "Stop").doClick();

        assertEquals(List.of("previous", "next", "seekRelative PT-5S", "seekRelative PT5S", "stop"),
                actions.calls);
    }

    @Test
    void disabledButtonsSendNothing() {
        NowPlayingPanel panel = new NowPlayingPanel(actions);
        panel.applySnapshot(idle());
        assertTrue(!button(panel, "Play").isEnabled());
        button(panel, "Play").doClick();
        button(panel, "Stop").doClick();
        assertEquals(List.of(), actions.calls);
    }

    @Test
    void seekSliderSendsAbsoluteSeekOnlyForUserChanges() {
        NowPlayingPanel panel = new NowPlayingPanel(actions);
        Track track = TestTracks.track("a", "A", "Artist", Duration.ofSeconds(10));
        panel.applySnapshot(snapshot(track, PlaybackState.PLAYING, 1));
        assertEquals(List.of(), actions.calls, "applying a snapshot must not seek");

        JSlider seek = findAll(panel, JSlider.class).get(0);
        seek.setValue(250);
        assertEquals(List.of("seek PT2.5S"), actions.calls);
    }

    @Test
    void snapshotSyncIsSilentForMixerControls() {
        ControlsPanel panel = new ControlsPanel(actions);
        panel.applySnapshot(new PlaybackSnapshot(null, PlaybackState.PAUSED, Duration.ZERO, Duration.ZERO,
                0.2, true, 2.0, 0.5, false, 0, 1, RepeatMode.ALL, true, null));
        assertEquals(List.of(), actions.calls, "snapshot sync must not send commands back");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void mixerControlsSendUserChanges() {
        ControlsPanel panel = new ControlsPanel(actions);
        panel.applySnapshot(idle());

        findAll(panel, JSlider.class).stream().filter(s -> s.getMinimum() == 0).findFirst()
                .orElseThrow().setValue(50);
        checkbox(panel, "Mute").doClick();
        findAll(panel, JSlider.class).stream().filter(s -> s.getMinimum() == -100).findFirst()
                .orElseThrow().setValue(-50);
        List<JComboBox> combos = findAll(panel, JComboBox.class);
        combos.get(0).setSelectedItem(1.5);
        combos.get(1).setSelectedItem(RepeatMode.ALL);
        checkbox(panel, "Shuffle").doClick();

        assertEquals(List.of("volume 0.5", "mute true", "pan -0.5", "speed 1.5", "repeat ALL", "shuffle true"),
                actions.calls);
    }

    @Test
    void queuePanelButtonsSendQueueEdits() {
        QueuePanel panel = new QueuePanel(actions);
        Track a = TestTracks.track("a", "A", null, Duration.ofSeconds(1));
        Track b = TestTracks.track("b", "B", null, Duration.ofSeconds(2));
        panel.applyQueue(List.of(a, b), new QueueSnapshot(0, 2, RepeatMode.OFF, false));
        assertTrue(!button(panel, "Move up").isEnabled(), "first row cannot move up");

        JTable table = findAll(panel, JTable.class).get(0);
        table.setRowSelectionInterval(1, 1);
        button(panel, "Move up").doClick();
        assertEquals(List.of("move 1 0"), actions.calls);

        actions.calls.clear();
        table.setRowSelectionInterval(0, 0);
        button(panel, "Remove").doClick();
        button(panel, "Clear").doClick();
        assertEquals(List.of("removeAt 0", "clear"), actions.calls);
    }

    @Test
    void speedAndPanText() {
        assertEquals(1.25, ControlsPanel.nearestSpeed(1.3));
        assertEquals(2.0, ControlsPanel.nearestSpeed(9.0));
        assertEquals("centre", ControlsPanel.describePan(0.0));
        assertEquals("left 50%", ControlsPanel.describePan(-0.5));
        assertEquals("right 100%", ControlsPanel.describePan(1.0));
    }

    static JButton button(Container root, String text) {
        return findAll(root, JButton.class).stream()
                .filter(b -> text.equals(b.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no button " + text));
    }

    static JCheckBox checkbox(Container root, String text) {
        return findAll(root, JCheckBox.class).stream()
                .filter(c -> text.equals(c.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no checkbox " + text));
    }

    static <T extends Component> List<T> findAll(Container root, Class<T> type) {
        List<T> found = new ArrayList<>();
        collect(root, type, found);
        return found;
    }

    private static <T extends Component> void collect(Container root, Class<T> type, List<T> out) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child)) {
                out.add(type.cast(child));
            }
            if (child instanceof Container container) {
                collect(container, type, out);
            }
        }
    }

    /** Records each command as text so tests can compare the exact sequence. */
    static final class RecordingActions implements PlayerActions {
        final List<String> calls = new ArrayList<>();

        @Override public void play() { calls.add("play"); }
        @Override public void pause() { calls.add("pause"); }
        @Override public void resume() { calls.add("resume"); }
        @Override public void stop() { calls.add("stop"); }
        @Override public void next() { calls.add("next"); }
        @Override public void previous() { calls.add("previous"); }
        @Override public void seek(Duration target) { calls.add("seek " + target); }
        @Override public void seekRelative(Duration offset) { calls.add("seekRelative " + offset); }
        @Override public void playAt(int index) { calls.add("playAt " + index); }
        @Override public void setVolume(double volume) { calls.add("volume " + volume); }
        @Override public void setMuted(boolean muted) { calls.add("mute " + muted); }
        @Override public void setPan(double pan) { calls.add("pan " + pan); }
        @Override public void setSpeed(double speed) { calls.add("speed " + speed); }
        @Override public void setRepeat(RepeatMode mode) { calls.add("repeat " + mode); }
        @Override public void setShuffle(boolean enabled) { calls.add("shuffle " + enabled); }
        @Override public void addFiles(List<Path> files) { calls.add("addFiles " + files.size()); }
        @Override public void addDirectory(Path directory) { calls.add("addDirectory"); }
        @Override public void removeAt(int index) { calls.add("removeAt " + index); }
        @Override public void move(int from, int to) { calls.add("move " + from + " " + to); }
        @Override public void clearQueue() { calls.add("clear"); }
    }
}
