package io.github.sinaraz.gui;

import java.awt.BorderLayout;
import java.awt.Font;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.border.TitledBorder;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultCaret;

/** Scrolling, bounded event log. Must be called from the Swing event thread. */
final class LogPanel extends JPanel {

    private static final int MAX_LINES = 500;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JTextArea area = new JTextArea(6, 80);

    LogPanel() {
        super(new BorderLayout());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ((DefaultCaret) area.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        add(new JScrollPane(area), BorderLayout.CENTER);
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createEmptyBorder(), "Event log", TitledBorder.LEFT, TitledBorder.TOP));
    }

    void log(String message) {
        append(message);
    }

    void error(String message) {
        append("ERROR  " + message);
    }

    private void append(String message) {
        area.append(TIME.format(LocalTime.now()) + "  " + message + "\n");
        int lines = area.getLineCount();
        // Trim in batches: trimming on every line is O(document) per append.
        if (lines > MAX_LINES + 100) {
            try {
                area.replaceRange("", 0, area.getLineStartOffset(lines - MAX_LINES));
            } catch (BadLocationException e) {
                // Trimming is best effort; the log stays usable without it.
            }
        }
        area.setCaretPosition(area.getDocument().getLength());
    }
}
