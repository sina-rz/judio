package io.github.sinaraz.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;

import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.Track;

/** Cover art, track text, seek bar and transport buttons. */
final class NowPlayingPanel extends JPanel {

    private static final int COVER_SIZE = 240;
    static final int SEEK_STEPS = 1000;
    private static final Duration SKIP = Duration.ofSeconds(5);

    private final PlayerActions actions;
    private final JLabel cover = new JLabel("No cover art", SwingConstants.CENTER);
    private final JLabel title = new JLabel("Nothing loaded");
    private final JLabel subtitle = new JLabel(" ");
    private final JLabel error = new JLabel(" ");
    private final JLabel time = new JLabel("--:-- / --:--");
    private final JSlider seek = new JSlider(0, SEEK_STEPS, 0);
    private final JButton previous = new JButton("Previous");
    private final JButton back = new JButton("-5 s");
    private final JButton playPause = new JButton("Play");
    private final JButton stop = new JButton("Stop");
    private final JButton forward = new JButton("+5 s");
    private final JButton next = new JButton("Next");

    private PlaybackSnapshot current;
    private boolean syncing;

    NowPlayingPanel(PlayerActions actions) {
        super(new BorderLayout(0, 12));
        this.actions = Objects.requireNonNull(actions, "actions");
        setBorder(new EmptyBorder(12, 12, 12, 12));

        cover.setPreferredSize(new Dimension(COVER_SIZE, COVER_SIZE));
        cover.setMinimumSize(new Dimension(COVER_SIZE, COVER_SIZE));
        cover.setBorder(new LineBorder(Color.GRAY));
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));
        error.setForeground(new Color(0xB00020));
        time.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));

        JPanel info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        for (JLabel label : new JLabel[] {title, subtitle}) {
            label.setAlignmentX(LEFT_ALIGNMENT);
            info.add(label);
        }
        info.add(Box.createVerticalStrut(16));
        error.setAlignmentX(LEFT_ALIGNMENT);
        info.add(error);
        info.add(Box.createVerticalGlue());

        JPanel top = new JPanel(new BorderLayout(16, 0));
        top.add(cover, BorderLayout.WEST);
        top.add(info, BorderLayout.CENTER);

        seek.setEnabled(false);
        seek.addChangeListener(event -> onSeekChanged());
        JPanel seekRow = new JPanel(new BorderLayout(8, 0));
        seekRow.add(seek, BorderLayout.CENTER);
        seekRow.add(time, BorderLayout.EAST);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(previous);
        buttons.add(back);
        buttons.add(playPause);
        buttons.add(stop);
        buttons.add(forward);
        buttons.add(next);

        JPanel south = new JPanel(new BorderLayout(0, 12));
        south.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        south.add(seekRow, BorderLayout.NORTH);
        south.add(buttons, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(south, BorderLayout.SOUTH);

        previous.addActionListener(event -> actions.previous());
        next.addActionListener(event -> actions.next());
        back.addActionListener(event -> actions.seekRelative(SKIP.negated()));
        forward.addActionListener(event -> actions.seekRelative(SKIP));
        stop.addActionListener(event -> actions.stop());
        playPause.addActionListener(event -> togglePlayPause());
    }

    /** Play when stopped, pause while playing, resume while paused. */
    void togglePlayPause() {
        PlaybackState state = current == null ? PlaybackState.EMPTY : current.state();
        switch (state) {
            case PLAYING -> actions.pause();
            case PAUSED -> actions.resume();
            default -> actions.play();
        }
    }

    void applySnapshot(PlaybackSnapshot snapshot) {
        current = snapshot;
        syncing = true;
        try {
            boolean loaded = snapshot.state().hasLoadedTrack();
            boolean hasDuration = loaded && snapshot.duration() != null && !snapshot.duration().isZero();
            Track track = snapshot.track();
            title.setText(track == null ? "Nothing loaded" : track.displayName());
            subtitle.setText(subtitleOf(track));
            error.setText(snapshot.error() == null ? " " : Labels.errorText(snapshot.error()));
            time.setText(TimeFormat.format(snapshot.position()) + " / "
                    + (hasDuration ? TimeFormat.format(snapshot.duration()) : "--:--"));
            if (!seek.getValueIsAdjusting()) {
                seek.setValue(hasDuration ? fraction(snapshot) : 0);
            }
            seek.setEnabled(hasDuration);
            boolean hasQueue = snapshot.queueSize() > 0;
            previous.setEnabled(hasQueue);
            next.setEnabled(hasQueue);
            back.setEnabled(loaded);
            forward.setEnabled(loaded);
            stop.setEnabled(loaded);
            playPause.setEnabled(hasQueue || loaded);
            playPause.setText(snapshot.state() == PlaybackState.PLAYING ? "Pause" : "Play");
        } finally {
            syncing = false;
        }
    }

    /** Shows the cover image, or the placeholder when {@code image} is null. */
    void showCover(BufferedImage image) {
        if (image == null) {
            cover.setIcon(null);
            cover.setText("No cover art");
            return;
        }
        Image scaled = image.getScaledInstance(COVER_SIZE, COVER_SIZE, Image.SCALE_SMOOTH);
        cover.setText(null);
        cover.setIcon(new ImageIcon(scaled));
    }

    private void onSeekChanged() {
        if (syncing || current == null || !current.state().hasLoadedTrack()) {
            return;
        }
        Duration total = current.duration();
        if (total == null || total.isZero()) {
            return;
        }
        Duration target = durationFor(seek.getValue(), total);
        if (seek.getValueIsAdjusting()) {
            time.setText(TimeFormat.format(target) + " / " + TimeFormat.format(total));
            return;
        }
        actions.seek(target);
    }

    static int fraction(PlaybackSnapshot snapshot) {
        double total = snapshot.duration().toMillis();
        double value = snapshot.position().toMillis() / total * SEEK_STEPS;
        return (int) Math.max(0, Math.min(SEEK_STEPS, Math.round(value)));
    }

    static Duration durationFor(int sliderValue, Duration total) {
        long millis = Math.round(total.toMillis() * (sliderValue / (double) SEEK_STEPS));
        return Duration.ofMillis(millis);
    }

    private static String subtitleOf(Track track) {
        if (track == null) {
            return " ";
        }
        StringBuilder text = new StringBuilder();
        if (track.artist() != null) {
            text.append(track.artist());
        }
        if (track.album() != null) {
            if (!text.isEmpty()) {
                text.append(" \u2014 ");
            }
            text.append(track.album());
        }
        return text.isEmpty() ? track.location().toString() : text.toString();
    }
}
