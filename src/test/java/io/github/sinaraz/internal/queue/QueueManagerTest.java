package io.github.sinaraz.internal.queue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.error.QueueException;
import io.github.sinaraz.model.RemoveResult;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueManagerTest {

    private static Track t(String name) {
        return Track.fromPath(Path.of("/music/" + name + ".mp3"));
    }

    private static List<Track> tracks(int n) {
        List<Track> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(t("t" + i));
        }
        return list;
    }

    private static QueueManager queueWith(int n) {
        QueueManager q = new QueueManager(new Random(1));
        q.addAll(tracks(n));
        return q;
    }

    @Test
    void addKeepsInsertionOrderAndDoesNotSelectAutomatically() {
        QueueManager q = queueWith(3);
        assertEquals(3, q.size());
        assertEquals(-1, q.currentIndex());
        assertEquals(List.of("t0", "t1", "t2"), names(q.snapshot()));
    }

    @Test
    void insertBeforeCurrentShiftsCurrentIndex() {
        QueueManager q = queueWith(3);
        q.playAt(1);
        q.insert(0, t("new"));
        assertEquals(2, q.currentIndex());
        assertEquals("t1", q.current().orElseThrow().displayName());
        q.insert(4, t("tail"));
        assertEquals(2, q.currentIndex());
    }

    @Test
    void removeBeforeCurrentShiftsIndexAndRemoveCurrentSelectsFollowing() {
        QueueManager q = queueWith(4);
        q.playAt(2);
        RemoveResult before = q.remove(0);
        assertFalse(before.currentRemoved());
        assertEquals(1, q.currentIndex());
        assertEquals("t2", q.current().orElseThrow().displayName());

        RemoveResult current = q.remove(1);
        assertTrue(current.currentRemoved());
        assertEquals("t3", current.newCurrent().displayName());
        assertEquals(1, q.currentIndex());
    }

    @Test
    void removingLastCurrentSelectsPreviousAndRemovingOnlyTrackEmptiesCurrent() {
        QueueManager q = queueWith(2);
        q.playAt(1);
        RemoveResult r = q.remove(1);
        assertTrue(r.currentRemoved());
        assertEquals(0, q.currentIndex());

        RemoveResult last = q.remove(0);
        assertTrue(last.currentRemoved());
        assertNull(last.newCurrent());
        assertEquals(-1, q.currentIndex());
        assertEquals(0, q.size());
    }

    @Test
    void removeOutOfRangeThrows() {
        QueueManager q = queueWith(2);
        assertThrows(QueueException.class, () -> q.remove(2));
        assertThrows(QueueException.class, () -> q.remove(-1));
        assertThrows(QueueException.class, () -> new QueueManager().remove(0));
    }

    @Test
    void moveKeepsCurrentTrackCurrent() {
        QueueManager q = queueWith(4);
        q.playAt(1); // t1
        q.move(0, 3); // t0 goes to the end: [t1, t2, t3, t0]
        assertEquals(List.of("t1", "t2", "t3", "t0"), names(q.snapshot()));
        assertEquals(0, q.currentIndex());
        q.move(3, 0);
        assertEquals("t1", q.current().orElseThrow().displayName());
        assertEquals(1, q.currentIndex());
    }

    @Test
    void clearEmptiesQueueAndCurrent() {
        QueueManager q = queueWith(3);
        q.playAt(2);
        q.clear();
        assertEquals(0, q.size());
        assertEquals(-1, q.currentIndex());
        assertTrue(q.next().isEmpty());
        assertTrue(q.previous().isEmpty());
    }

    @Test
    void emptyQueueNextAndPreviousReturnEmpty() {
        QueueManager q = new QueueManager(new Random(1));
        assertTrue(q.next().isEmpty());
        assertTrue(q.previous().isEmpty());
        assertTrue(q.onTrackEnded().isEmpty());
    }

    @Test
    void nextAndPreviousStopAtBoundariesWithRepeatOff() {
        QueueManager q = queueWith(2);
        assertEquals("t0", q.next().orElseThrow().displayName());
        assertEquals("t1", q.next().orElseThrow().displayName());
        assertTrue(q.next().isEmpty(), "no wrap-around when repeat is off");
        assertEquals(1, q.currentIndex());
        assertEquals("t0", q.previous().orElseThrow().displayName());
        assertTrue(q.previous().isEmpty());
    }

    @Test
    void repeatAllWrapsAroundInBothDirections() {
        QueueManager q = queueWith(2);
        q.setRepeat(RepeatMode.ALL);
        q.playAt(1);
        assertEquals("t0", q.next().orElseThrow().displayName());
        assertEquals("t1", q.previous().orElseThrow().displayName());
    }

    @Test
    void repeatOneReplaysCurrentOnNaturalEndButNextStillAdvances() {
        QueueManager q = queueWith(3);
        q.setRepeat(RepeatMode.ONE);
        q.playAt(1);
        assertEquals(1, q.currentIndex());
        assertEquals("t1", q.onTrackEnded().orElseThrow().displayName());
        assertEquals(1, q.currentIndex());
        assertEquals("t2", q.next().orElseThrow().displayName());
    }

    @Test
    void shuffleIsDeterministicWithSeededRandomAndKeepsUserOrder() {
        QueueManager a = new QueueManager(new Random(42));
        QueueManager b = new QueueManager(new Random(42));
        a.addAll(tracks(8));
        b.addAll(tracks(8));
        a.setShuffle(true);
        b.setShuffle(true);
        assertIterableEquals(names(a.playbackOrder()), names(b.playbackOrder()));
        assertEquals(List.of("t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7"), names(a.snapshot()),
                "shuffle must not reorder the user-visible queue");
        assertEquals(8, a.playbackOrder().stream().map(Track::id).distinct().count());
    }

    @Test
    void shuffleOnlyChangesPlaybackSequenceAndPreviousWalksBackHistory() {
        QueueManager q = new QueueManager(new Random(7));
        q.addAll(tracks(5));
        q.setShuffle(true);
        q.playAt(0);
        String first = q.current().orElseThrow().id();
        Track second = q.next().orElseThrow();
        Track third = q.next().orElseThrow();
        assertEquals(second.id(), q.previous().orElseThrow().id());
        assertEquals(first, q.previous().orElseThrow().id());
        assertTrue(q.previous().isEmpty(), "history is exhausted");
        assertEquals(second.id(), q.next().orElseThrow().id());
        assertEquals(third.id(), q.next().orElseThrow().id());
    }

    @Test
    void disablingShuffleReturnsToUserOrder() {
        QueueManager q = queueWith(4);
        q.setShuffle(true);
        q.setShuffle(false);
        assertEquals(names(q.snapshot()), names(q.playbackOrder()));
    }

    @Test
    void removingTrackInShuffleOrderKeepsPlaybackConsistent() {
        QueueManager q = new QueueManager(new Random(3));
        q.addAll(tracks(4));
        q.setShuffle(true);
        q.playAt(0);
        Track victim = q.snapshot().get(2);
        int index = q.snapshot().indexOf(victim);
        q.remove(index);
        assertEquals(3, q.size());
        assertEquals(3, q.playbackOrder().size());
        assertFalse(q.playbackOrder().contains(victim));
    }

    @Test
    void updateReplacesStoredTrackAndMissingIdIsEmpty() {
        QueueManager q = queueWith(2);
        Track original = q.snapshot().get(0);
        Optional<Track> updated = q.update(original.id(), x -> x.withErrorMessage("boom"));
        assertEquals("boom", updated.orElseThrow().errorMessage());
        assertEquals("boom", q.snapshot().get(0).errorMessage());
        assertTrue(q.update("missing", x -> x).isEmpty());
    }

    @Test
    void addDuplicatePathCreatesDistinctEntries() {
        QueueManager q = new QueueManager(new Random(1));
        Track a = t("same");
        Track b = t("same");
        q.addAll(List.of(a, b));
        assertEquals(2, q.size());
        assertFalse(a.id().equals(b.id()));
    }

    private static List<String> names(List<Track> list) {
        return list.stream().map(x -> x.location().displayName().replace(".mp3", "")).toList();
    }
}
