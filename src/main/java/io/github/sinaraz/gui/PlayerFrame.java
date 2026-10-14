package io.github.sinaraz.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Toolkit;
import java.awt.event.ActionListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.Track;

/**
 * Main window. Engine events arrive on the bus thread and are posted to the event thread. The
 * frame wires them to the panels; all commands go through {@link FacadeActions}.
 */
final class PlayerFrame extends JFrame {

    private final JudioPlayer player;
    private final FacadeActions actions;
    private final NowPlayingPanel nowPlaying;
    private final ControlsPanel controls;
    private final QueuePanel queue;
    private final LogPanel log = new LogPanel();
    private final JLabel stateLabel = new JLabel(Labels.stateText(PlaybackState.EMPTY));
    private final JLabel modeLabel = new JLabel();
    private final JLabel errorLabel = new JLabel("Errors: 0");
    private PlaybackState lastState = PlaybackState.EMPTY;
    private int errorCount;
    private long coverToken;
    private SwingWorker<BufferedImage, Void> coverWorker;
    private boolean shuttingDown;

    PlayerFrame(JudioPlayer player, boolean simulatedAudio, List<Path> initialFiles) {
        super("judio");
        this.player = Objects.requireNonNull(player, "player");
        this.actions = new FacadeActions(player,
                message -> SwingUtilities.invokeLater(() -> reportError("command", message)));
        this.nowPlaying = new NowPlayingPanel(actions);
        this.controls = new ControlsPanel(actions);
        this.queue = new QueuePanel(actions);

        JPanel right = new JPanel(new BorderLayout(0, 12));
        right.setBorder(BorderFactory.createEmptyBorder(8, 4, 8, 8));
        right.add(nowPlaying, BorderLayout.NORTH);
        right.add(controls, BorderLayout.CENTER);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, queue, right);
        main.setResizeWeight(0.55);
        JSplitPane vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, main, log);
        vertical.setResizeWeight(0.8);
        vertical.setBorder(BorderFactory.createEmptyBorder());

        JPanel status = new JPanel(new BorderLayout(12, 0));
        status.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        status.add(stateLabel, BorderLayout.WEST);
        status.add(modeLabel, BorderLayout.CENTER);
        status.add(errorLabel, BorderLayout.EAST);

        modeLabel.setText(simulatedAudio ? "Output: simulated real-time device" : "Output: system audio");
        add(vertical, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
        setJMenuBar(buildMenuBar());

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                shutdown();
            }
        });
        setMinimumSize(new Dimension(900, 620));
        setSize(1180, 760);
        setLocationByPlatform(true);

        player.addListener(new UiListener());
        log.log(modeLabel.getText());
        if (!initialFiles.isEmpty()) {
            actions.addFiles(initialFiles);
        }
    }

    private JMenuBar buildMenuBar() {
        int mask = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        JMenu file = new JMenu("File");
        file.add(menuItem("Add files...", KeyStroke.getKeyStroke(KeyEvent.VK_O, mask), event -> queue.chooseFiles()));
        file.add(menuItem("Add folder...",
                KeyStroke.getKeyStroke(KeyEvent.VK_O, mask | InputEvent.SHIFT_DOWN_MASK),
                event -> queue.chooseFolder()));
        file.addSeparator();
        file.add(menuItem("Clear queue", null, event -> actions.clearQueue()));
        file.addSeparator();
        file.add(menuItem("Quit", KeyStroke.getKeyStroke(KeyEvent.VK_Q, mask), event -> shutdown()));

        JMenu playback = new JMenu("Playback");
        playback.add(menuItem("Play / Pause",
                KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, InputEvent.CTRL_DOWN_MASK),
                event -> togglePlayPause()));
        playback.add(menuItem("Stop", KeyStroke.getKeyStroke(KeyEvent.VK_PERIOD, mask), event -> actions.stop()));
        playback.add(menuItem("Previous", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, mask), event -> actions.previous()));
        playback.add(menuItem("Next", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, mask), event -> actions.next()));
        playback.add(menuItem("Back 5 s", KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK),
                event -> actions.seekRelative(Duration.ofSeconds(-5))));
        playback.add(menuItem("Forward 5 s", KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK),
                event -> actions.seekRelative(Duration.ofSeconds(5))));

        JMenu help = new JMenu("Help");
        help.add(menuItem("About", null, event -> showAbout()));

        JMenuBar bar = new JMenuBar();
        bar.add(file);
        bar.add(playback);
        bar.add(help);
        return bar;
    }

    private static JMenuItem menuItem(String text, KeyStroke accelerator, ActionListener action) {
        JMenuItem item = new JMenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.addActionListener(action);
        return item;
    }

    private void togglePlayPause() {
        switch (lastState) {
            case PLAYING -> actions.pause();
            case PAUSED -> actions.resume();
            default -> actions.play();
        }
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this,
                "<html><b>judio</b><br>"
                        + "Pure-Java playback engine with a Swing front end.<br><br>"
                        + "Formats: MP3, WAV, AIFF, AU. FLAC and OGG are not decoded.<br>"
                        + "Speed uses resampling, so it also changes pitch.<br>"
                        + "Keyboard: Ctrl+Space play/pause, Ctrl+. stop, Ctrl+Left/Right previous/next,"
                        + " Alt+Left/Right seek 5 s.</html>",
                "About", JOptionPane.INFORMATION_MESSAGE);
    }

    private void applySnapshot(PlaybackSnapshot snapshot) {
        lastState = snapshot.state();
        stateLabel.setText(Labels.stateText(snapshot.state()));
        nowPlaying.applySnapshot(snapshot);
        controls.applySnapshot(snapshot);
    }

    private void trackLoaded(Track track, AudioInfo info) {
        log.log("Loaded " + track.displayName() + " (" + TimeFormat.format(info.duration()) + ", "
                + info.sampleRate() + " Hz, " + info.channels() + " ch, " + info.decoderName() + ")");
        loadCover(track);
    }

    private void loadCover(Track track) {
        long token = ++coverToken;
        nowPlaying.showCover(null);
        if (coverWorker != null) {
            coverWorker.cancel(true); // superseded: stop its file I/O early
        }
        coverWorker = new SwingWorker<BufferedImage, Void>() {
            @Override
            protected BufferedImage doInBackground() throws IOException {
                Optional<CoverArt> art = player.coverArt(track);
                if (art.isEmpty()) {
                    return null;
                }
                return ImageIO.read(new ByteArrayInputStream(art.get().data()));
            }

            @Override
            protected void done() {
                if (token != coverToken) {
                    return;
                }
                try {
                    nowPlaying.showCover(get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    log.error("cover art: " + (e.getCause() == null ? e : e.getCause()).getMessage());
                    nowPlaying.showCover(null);
                }
            }
        };
        coverWorker.execute();
    }

    private void reportError(String where, String message) {
        errorCount++;
        errorLabel.setText("Errors: " + errorCount);
        log.error(where + ": " + message);
    }

    /** Closes the player off the event thread, then exits. Safe to call more than once. */
    void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        stateLabel.setText("Shutting down...");
        Thread closer = new Thread(() -> {
            actions.close();
            SwingUtilities.invokeLater(() -> {
                dispose();
                System.exit(0);
            });
        }, "player-close");
        closer.start();
    }

    /** Engine listener. Each callback posts its work to the Swing event thread. */
    private final class UiListener implements PlaybackListener {
        @Override
        public void onStateChanged(PlaybackSnapshot snapshot) {
            SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
        }

        @Override
        public void onProgress(PlaybackSnapshot snapshot) {
            SwingUtilities.invokeLater(() -> applySnapshot(snapshot));
        }

        @Override
        public void onTrackLoaded(Track track, AudioInfo info) {
            SwingUtilities.invokeLater(() -> trackLoaded(track, info));
        }

        @Override
        public void onQueueChanged(List<Track> tracks, QueueSnapshot state) {
            SwingUtilities.invokeLater(() -> queue.applyQueue(tracks, state));
        }

        @Override
        public void onPlaybackCompleted(Track track) {
            SwingUtilities.invokeLater(() -> log.log("Finished " + track.displayName()));
        }

        @Override
        public void onError(PlaybackError error) {
            SwingUtilities.invokeLater(() -> reportError(error.operation(), Labels.errorText(error)));
        }
    }
}
