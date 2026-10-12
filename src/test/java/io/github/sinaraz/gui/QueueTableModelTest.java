package io.github.sinaraz.gui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.model.Track;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.TrackMetadataStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueTableModelTest {

    @Test
    void showsRowsAndMarksCurrentTrack() {
        QueueTableModel model = new QueueTableModel();
        Track first = TestTracks.track("one", "One", "Artist A", Duration.ofSeconds(65));
        Track second = TestTracks.track("two", "Two", null, null);
        model.update(List.of(first, second), 1);

        assertEquals(2, model.getRowCount());
        assertEquals(QueueTableModel.COLUMNS.length, model.getColumnCount());
        assertEquals("1", model.getValueAt(0, 0));
        assertEquals("\u25B6", model.getValueAt(1, 0));
        assertEquals("One", model.getValueAt(0, 1));
        assertEquals("Artist A", model.getValueAt(0, 2));
        assertEquals("01:05", model.getValueAt(0, 4));
        assertEquals("", model.getValueAt(1, 2));
        assertEquals("--:--", model.getValueAt(1, 4));
        assertFalse(model.isCellEditable(0, 1));
        assertEquals(second, model.trackAt(1));
    }

    @Test
    void detailsDescribeFormatOrError() {
        Track ok = TestTracks.track("ok", "Ok", null, Duration.ofSeconds(1));
        assertEquals("MPEG-1 Layer III \u00B7 44100 Hz \u00B7 2 ch", QueueTableModel.details(ok));

        Track failed = new Track("id-bad", MediaLocation.of(Path.of("/music/bad.mp3")), "bad", null, null, null, null, null,
                null, null, null, null, null, null, 0L, false, TrackMetadataStatus.FAILED, "truncated frame");
        assertTrue(QueueTableModel.details(failed).contains("truncated frame"));
    }

    @Test
    void updateReplacesContents() {
        QueueTableModel model = new QueueTableModel();
        model.update(List.of(TestTracks.track("a", "A", null, null)), 0);
        model.update(List.of(), -1);
        assertEquals(0, model.getRowCount());
    }
}
