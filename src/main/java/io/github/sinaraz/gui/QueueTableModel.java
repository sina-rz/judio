package io.github.sinaraz.gui;

import java.util.ArrayList;
import java.util.List;
import javax.swing.table.AbstractTableModel;

import io.github.sinaraz.model.Track;

/** Table model for the visible queue. The current track is marked in the first column. */
final class QueueTableModel extends AbstractTableModel {

    static final String[] COLUMNS = {"", "Title", "Artist", "Album", "Length", "Details"};
    private static final String PLAYING_MARKER = "\u25B6";

    private final List<Track> tracks = new ArrayList<>();
    private int currentIndex = -1;

    void update(List<Track> newTracks, int current) {
        tracks.clear();
        tracks.addAll(newTracks);
        currentIndex = current;
        fireTableDataChanged();
    }

    void updateTrack(Track updated) {
        for (int row = 0; row < tracks.size(); row++) {
            if (tracks.get(row).id().equals(updated.id())) {
                tracks.set(row, updated);
                fireTableRowsUpdated(row, row);
                return;
            }
        }
    }

    Track trackAt(int row) {
        return tracks.get(row);
    }

    @Override
    public int getRowCount() {
        return tracks.size();
    }

    @Override
    public int getColumnCount() {
        return COLUMNS.length;
    }

    @Override
    public String getColumnName(int column) {
        return COLUMNS[column];
    }

    @Override
    public boolean isCellEditable(int row, int column) {
        return false;
    }

    @Override
    public Object getValueAt(int row, int column) {
        Track track = tracks.get(row);
        return switch (column) {
            case 0 -> row == currentIndex ? PLAYING_MARKER : String.valueOf(row + 1);
            case 1 -> track.title() != null ? track.title() : track.location().displayName();
            case 2 -> nullToEmpty(track.artist());
            case 3 -> nullToEmpty(track.album());
            case 4 -> TimeFormat.format(track.duration());
            case 5 -> details(track);
            default -> throw new IndexOutOfBoundsException("column " + column);
        };
    }

    /** Describes the decoded format, or the error that stopped the track. */
    static String details(Track track) {
        if (track.errorMessage() != null) {
            return "Error: " + track.errorMessage();
        }
        StringBuilder text = new StringBuilder();
        if (track.codec() != null) {
            text.append(track.codec());
        }
        if (track.sampleRate() != null) {
            appendSeparator(text).append(track.sampleRate()).append(" Hz");
        }
        if (track.channels() != null) {
            appendSeparator(text).append(track.channels() == 1 ? "mono" : track.channels() + " ch");
        }
        if (text.isEmpty()) {
            return track.metadataStatus().name().toLowerCase(java.util.Locale.ROOT);
        }
        return text.toString();
    }

    private static StringBuilder appendSeparator(StringBuilder text) {
        if (!text.isEmpty()) {
            text.append(" \u00B7 ");
        }
        return text;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
