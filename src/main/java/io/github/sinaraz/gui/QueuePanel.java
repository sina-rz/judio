package io.github.sinaraz.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.TransferHandler;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.TableColumnModel;

import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.Track;

/** Visible queue: table, queue editing buttons, file dialogs and drag-and-drop. */
final class QueuePanel extends JPanel {

    private static final String[] AUDIO_EXTENSIONS = {"mp3", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff", "aifc", "au", "snd"};

    private final PlayerActions actions;
    private final QueueTableModel model = new QueueTableModel();
    private final JTable table = new JTable(model);
    private final JButton remove = new JButton("Remove");
    private final JButton moveUp = new JButton("Move up");
    private final JButton moveDown = new JButton("Move down");
    private final JButton clear = new JButton("Clear");
    private final JLabel summary = new JLabel("Queue is empty");
    private int pendingSelection = -1;

    QueuePanel(PlayerActions actions) {
        super(new BorderLayout(0, 8));
        this.actions = Objects.requireNonNull(actions, "actions");
        setBorder(BorderFactory.createTitledBorder("Queue"));

        table.setFillsViewportHeight(true);
        table.setAutoCreateRowSorter(false);
        table.setDragEnabled(false);
        TableColumnModel columns = table.getColumnModel();
        int[] widths = {36, 220, 150, 150, 70, 220};
        for (int i = 0; i < widths.length; i++) {
            columns.getColumn(i).setPreferredWidth(widths[i]);
        }
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2) {
                    int row = table.rowAtPoint(event.getPoint());
                    if (row >= 0) {
                        actions.playAt(row);
                    }
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateButtons();
            }
        });
        table.setTransferHandler(new FileDropHandler());

        JButton addFiles = new JButton("Add files...");
        JButton addFolder = new JButton("Add folder...");
        JButton openUrl = new JButton("Open URL...");
        openUrl.addActionListener(event -> openUrl());
        addFiles.addActionListener(event -> chooseFiles());
        addFolder.addActionListener(event -> chooseFolder());
        remove.addActionListener(event -> removeSelected());
        moveUp.addActionListener(event -> moveSelected(-1));
        moveDown.addActionListener(event -> moveSelected(1));
        clear.addActionListener(event -> {
            pendingSelection = -1;
            actions.clearQueue();
        });

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        for (JButton button : new JButton[] {addFiles, addFolder, openUrl, remove, moveUp, moveDown, clear}) {
            toolbar.add(button);
        }
        add(toolbar, BorderLayout.NORTH);
        add(new JScrollPane(table), BorderLayout.CENTER);
        add(summary, BorderLayout.SOUTH);
        updateButtons();
    }

    /** Replaces the table contents from a queue event. Runs on the Swing event thread. */
    void applyQueue(List<Track> tracks, QueueSnapshot state) {
        model.update(tracks, state.currentIndex());
        summary.setText(tracks.isEmpty()
                ? "Queue is empty"
                : tracks.size() + " track(s) \u00B7 repeat " + state.repeat()
                        + " \u00B7 shuffle " + (state.shuffle() ? "on" : "off"));
        if (pendingSelection >= 0 && pendingSelection < tracks.size()) {
            table.setRowSelectionInterval(pendingSelection, pendingSelection);
        }
        pendingSelection = -1;
        updateButtons();
    }

    /** Refreshes a single metadata-updated row. Runs on the Swing event thread. */
    void applyTrackMetadata(Track track) {
        model.updateTrack(track);
    }

    void chooseFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add audio files");
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileFilter(new FileNameExtensionFilter("Audio files (MP3, WAV, AIFF, AU)", AUDIO_EXTENSIONS));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            List<Path> files = Arrays.stream(chooser.getSelectedFiles()).map(File::toPath).toList();
            actions.addFiles(files);
        }
    }

    /** Asks for an http or https address and queues it as a stream. */
    void openUrl() {
        String text = JOptionPane.showInputDialog(this,
                "Stream address (http or https):", "Open URL", JOptionPane.PLAIN_MESSAGE);
        if (text == null || text.isBlank()) {
            return;
        }
        URI uri;
        try {
            uri = URI.create(text.trim());
        } catch (IllegalArgumentException e) {
            uri = null;
        }
        if (uri == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
            JOptionPane.showMessageDialog(this, "Enter a complete http:// or https:// address.",
                    "Open URL", JOptionPane.ERROR_MESSAGE);
            return;
        }
        actions.addUrl(uri);
    }

    void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Add a folder of audio files");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            actions.addDirectory(chooser.getSelectedFile().toPath());
        }
    }

    private void removeSelected() {
        int row = table.getSelectedRow();
        if (row < 0) {
            return;
        }
        pendingSelection = Math.min(row, model.getRowCount() - 2);
        actions.removeAt(row);
    }

    private void moveSelected(int direction) {
        int row = table.getSelectedRow();
        int target = row + direction;
        if (row < 0 || target < 0 || target >= model.getRowCount()) {
            return;
        }
        pendingSelection = target;
        actions.move(row, target);
    }

    private void updateButtons() {
        int row = table.getSelectedRow();
        boolean selected = row >= 0;
        remove.setEnabled(selected);
        moveUp.setEnabled(selected && row > 0);
        moveDown.setEnabled(selected && row < model.getRowCount() - 1);
        clear.setEnabled(model.getRowCount() > 0);
    }

    /** Accepts files and folders dropped on the table. Uses Swing's TransferHandler, which works headless. */
    private final class FileDropHandler extends TransferHandler {
        @Override
        public boolean canImport(TransferSupport support) {
            return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
        }

        @Override
        public boolean importData(TransferSupport support) {
            if (!canImport(support)) {
                return false;
            }
            try {
                @SuppressWarnings("unchecked")
                List<File> dropped = (List<File>) support.getTransferable()
                        .getTransferData(DataFlavor.javaFileListFlavor);
                List<Path> files = new ArrayList<>();
                for (File file : dropped) {
                    if (file.isDirectory()) {
                        actions.addDirectory(file.toPath());
                    } else {
                        files.add(file.toPath());
                    }
                }
                if (!files.isEmpty()) {
                    actions.addFiles(files);
                }
                return true;
            } catch (UnsupportedFlavorException | IOException e) {
                return false;
            }
        }
    }
}
