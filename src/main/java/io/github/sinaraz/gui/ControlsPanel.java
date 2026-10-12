package io.github.sinaraz.gui;

import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Locale;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSlider;

import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.RepeatMode;

/**
 * Volume, mute, pan, speed, repeat and shuffle. Snapshots update these controls without sending
 * commands back, so the player stays the single source of truth.
 */
final class ControlsPanel extends JPanel {

    static final Double[] SPEED_CHOICES = {0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0};

    private final PlayerActions actions;
    private final JSlider volume = new JSlider(0, 100, 100);
    private final JLabel volumeValue = new JLabel("100%");
    private final JCheckBox mute = new JCheckBox("Mute");
    private final JSlider pan = new JSlider(-100, 100, 0);
    private final JLabel panValue = new JLabel("centre");
    private final JComboBox<Double> speed = new JComboBox<>(SPEED_CHOICES);
    private final JLabel speedNote = new JLabel("Speed changes pitch");
    private final JComboBox<RepeatMode> repeat = new JComboBox<>(RepeatMode.values());
    private final JCheckBox shuffle = new JCheckBox("Shuffle");
    private final JCheckBox preservePitch = new JCheckBox("Preserve pitch");
    private final GridBagConstraints constraints = new GridBagConstraints();
    private int rows;
    private boolean syncing;

    ControlsPanel(PlayerActions actions) {
        super(new GridBagLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        setBorder(BorderFactory.createTitledBorder("Mixer"));
        constraints.insets = new Insets(4, 6, 4, 6);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;

        speed.setRenderer(new SpeedRenderer());
        speed.setSelectedItem(1.0);

        volume.addChangeListener(event -> {
            volumeValue.setText(volume.getValue() + "%");
            if (!syncing) {
                actions.setVolume(volume.getValue() / 100.0);
            }
        });
        mute.addActionListener(event -> {
            if (!syncing) {
                actions.setMuted(mute.isSelected());
            }
        });
        pan.addChangeListener(event -> {
            panValue.setText(describePan(pan.getValue() / 100.0));
            if (!syncing) {
                actions.setPan(pan.getValue() / 100.0);
            }
        });
        speed.addActionListener(event -> {
            if (!syncing) {
                actions.setSpeed((Double) speed.getSelectedItem());
            }
        });
        repeat.addActionListener(event -> {
            if (!syncing) {
                actions.setRepeat((RepeatMode) repeat.getSelectedItem());
            }
        });
        shuffle.addActionListener(event -> {
            if (!syncing) {
                actions.setShuffle(shuffle.isSelected());
            }
        });
        preservePitch.addActionListener(event -> {
            if (!syncing) {
                actions.setPreservePitch(preservePitch.isSelected());
            }
        });

        row("Volume", volume, volumeValue);
        row("", mute, null);
        row("Pan", pan, panValue);
        row("Speed", speed, speedNote);
        row("", preservePitch, null);
        row("Repeat", repeat, null);
        row("", shuffle, null);
        constraints.gridy = rows;
        constraints.weighty = 1.0;
        add(new JLabel(" "), constraints);
    }

    void applySnapshot(PlaybackSnapshot snapshot) {
        syncing = true;
        try {
            if (!volume.getValueIsAdjusting()) {
                volume.setValue((int) Math.round(snapshot.volume() * 100));
            }
            volumeValue.setText(volume.getValue() + "%");
            mute.setSelected(snapshot.muted());
            if (!pan.getValueIsAdjusting()) {
                pan.setValue((int) Math.round(snapshot.pan() * 100));
            }
            panValue.setText(describePan(snapshot.pan()));
            speed.setSelectedItem(nearestSpeed(snapshot.speed()));
            repeat.setSelectedItem(snapshot.repeat());
            shuffle.setSelected(snapshot.shuffle());
            preservePitch.setSelected(snapshot.preservePitch());
            speedNote.setText(snapshot.preservePitch() ? "Speed keeps pitch" : "Speed changes pitch");
        } finally {
            syncing = false;
        }
    }

    private void row(String label, java.awt.Component control, JLabel extra) {
        constraints.gridy = rows;
        constraints.gridx = 0;
        constraints.weightx = 0;
        add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        add(control, constraints);
        if (extra != null) {
            constraints.gridx = 2;
            constraints.weightx = 0;
            add(extra, constraints);
        }
        rows++;
    }

    static Double nearestSpeed(double value) {
        Double best = SPEED_CHOICES[0];
        for (Double choice : SPEED_CHOICES) {
            if (Math.abs(choice - value) < Math.abs(best - value)) {
                best = choice;
            }
        }
        return best;
    }

    static String describePan(double pan) {
        if (Math.abs(pan) < 0.005) {
            return "centre";
        }
        int percent = (int) Math.round(Math.abs(pan) * 100);
        return pan < 0 ? "left " + percent + "%" : "right " + percent + "%";
    }

    /** Shows speeds as {@code 1.00x}. */
    private static final class SpeedRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            if (value instanceof Double speed) {
                setText(String.format(Locale.ROOT, "%.2fx", speed));
            }
            return this;
        }
    }
}
