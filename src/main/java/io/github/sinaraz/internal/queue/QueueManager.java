package io.github.sinaraz.internal.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.random.RandomGenerator;

import io.github.sinaraz.error.QueueException;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.RemoveResult;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

/**
 * Thread-safe playlist with repeat and shuffle semantics.
 *
 * <h2>Two orders</h2>
 * The <em>user order</em> ({@link #snapshot()}) is what the user sees and edits. When shuffle
 * is enabled a separate <em>playback order</em> (a list of track IDs) is maintained. Reordering
 * the user order never changes the playback order, and vice versa. Shuffle history is a stack
 * of IDs that {@link #previous()} walks back through.
 *
 * <h2>Previous semantics</h2>
 * This class only selects tracks by index. The "restart the current track if we are more than
 * N seconds in" rule needs the playback position, so it lives in
 * {@code JudioPlayer.previous()}.
 *
 * <h2>Current-track removal</h2>
 * Removing the current entry makes the following entry current (or the previous one if the
 * removed entry was last). Playback is not started; the caller decides what to do.
 *
 * <p>Shuffle order is produced by an injected {@link RandomGenerator}, so tests can use a
 * seeded generator to get deterministic results.
 */
public final class QueueManager {

    /** A mutually consistent immutable snapshot of the visible queue and its state. */
    public record View(List<Track> tracks, QueueSnapshot state) {}

    private final RandomGenerator random;
    private final ReentrantLock lock = new ReentrantLock();
    private final List<Track> tracks = new ArrayList<>();
    private final List<String> playbackOrder = new ArrayList<>();
    private final Deque<String> shuffleHistory = new ArrayDeque<>();
    private int currentIndex = -1;
    private RepeatMode repeat = RepeatMode.OFF;
    private boolean shuffle;

    public QueueManager() {
        this(RandomGenerator.getDefault());
    }

    public QueueManager(RandomGenerator random) {
        this.random = Objects.requireNonNull(random, "random");
    }

    public void add(Track track) {
        addAll(List.of(track));
    }

    public void addAll(List<Track> newTracks) {
        Objects.requireNonNull(newTracks, "newTracks");
        for (Track t : newTracks) {
            Objects.requireNonNull(t, "track");
        }
        lock.lock();
        try {
            for (Track t : newTracks) {
                tracks.add(t);
                if (shuffle) {
                    playbackOrder.add(random.nextInt(playbackOrder.size() + 1), t.id());
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Inserts at {@code index} (0..size inclusive). */
    public void insert(int index, Track track) {
        Objects.requireNonNull(track, "track");
        lock.lock();
        try {
            checkIndex("insert", index, 0, tracks.size());
            tracks.add(index, track);
            if (currentIndex >= index) {
                currentIndex++;
            }
            if (shuffle) {
                playbackOrder.add(random.nextInt(playbackOrder.size() + 1), track.id());
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Removes the entry at {@code index}.
     *
     * @return what was removed and what is now current
     */
    public RemoveResult remove(int index) {
        lock.lock();
        try {
            checkIndex("remove", index, 0, tracks.size() - 1);
            Track removed = tracks.remove(index);
            boolean wasCurrent = index == currentIndex;
            if (index < currentIndex) {
                currentIndex--;
            } else if (wasCurrent) {
                currentIndex = tracks.isEmpty() ? -1 : Math.min(index, tracks.size() - 1);
            }
            playbackOrder.remove(removed.id());
            shuffleHistory.removeIf(id -> id.equals(removed.id()));
            Track newCurrent = currentIndex >= 0 ? tracks.get(currentIndex) : null;
            return new RemoveResult(removed, wasCurrent, newCurrent);
        } finally {
            lock.unlock();
        }
    }

    /** Moves the entry at {@code from} so that it ends up at index {@code to}. */
    public void move(int from, int to) {
        lock.lock();
        try {
            checkIndex("move", from, 0, tracks.size() - 1);
            checkIndex("move", to, 0, tracks.size() - 1);
            String currentId = currentId();
            Track moved = tracks.remove(from);
            tracks.add(to, moved);
            if (currentId != null) {
                currentIndex = indexOfId(currentId);
            }
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            tracks.clear();
            playbackOrder.clear();
            shuffleHistory.clear();
            currentIndex = -1;
        } finally {
            lock.unlock();
        }
    }

    /** Makes the entry at {@code index} current and returns it. */
    public Track playAt(int index) {
        lock.lock();
        try {
            checkIndex("playAt", index, 0, tracks.size() - 1);
            if (shuffle && currentIndex >= 0) {
                shuffleHistory.push(currentId());
            }
            currentIndex = index;
            return tracks.get(index);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Manual "next". Honours shuffle and repeat-all wrap-around.
     *
     * @return the new current track, or empty when the end of the queue is reached
     *         with repeat disabled
     */
    public Optional<Track> next() {
        lock.lock();
        try {
            if (tracks.isEmpty()) {
                return Optional.empty();
            }
            if (shuffle) {
                return nextShuffled();
            }
            int candidate = currentIndex + 1;
            if (candidate >= tracks.size()) {
                if (repeat != RepeatMode.ALL) {
                    return Optional.empty();
                }
                candidate = 0;
            }
            currentIndex = candidate;
            return Optional.of(tracks.get(candidate));
        } finally {
            lock.unlock();
        }
    }

    /** Manual "previous" by queue order. Position-based restart is handled by the facade. */
    public Optional<Track> previous() {
        lock.lock();
        try {
            if (tracks.isEmpty()) {
                return Optional.empty();
            }
            if (shuffle) {
                return previousShuffled();
            }
            if (currentIndex > 0) {
                currentIndex--;
                return Optional.of(tracks.get(currentIndex));
            }
            if (currentIndex == 0 && repeat == RepeatMode.ALL) {
                currentIndex = tracks.size() - 1;
                return Optional.of(tracks.get(currentIndex));
            }
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Automatic advance at natural end of media: repeat-one replays the current entry,
     * otherwise this behaves like {@link #next()}.
     */
    public Optional<Track> onTrackEnded() {
        lock.lock();
        try {
            if (repeat == RepeatMode.ONE && currentIndex >= 0) {
                return Optional.of(tracks.get(currentIndex));
            }
            return next();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Replaces the entry with the given ID by applying {@code update} to the stored value.
     * Using a function instead of a value avoids overwriting fields that another thread has
     * just set (for example, metadata arriving after the audio duration was measured).
     *
     * @return the updated track, or empty if no entry has that ID any more
     */
    public Optional<Track> update(String id, UnaryOperator<Track> update) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(update, "update");
        lock.lock();
        try {
            int index = indexOfId(id);
            if (index < 0) {
                return Optional.empty();
            }
            Track updated = Objects.requireNonNull(update.apply(tracks.get(index)), "updated track");
            tracks.set(index, updated);
            return Optional.of(updated);
        } finally {
            lock.unlock();
        }
    }

    public void setRepeat(RepeatMode mode) {
        Objects.requireNonNull(mode, "mode");
        lock.lock();
        try {
            repeat = mode;
        } finally {
            lock.unlock();
        }
    }

    public RepeatMode repeat() {
        lock.lock();
        try {
            return repeat;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Enables or disables shuffle. Enabling builds a fresh playback order with the current
     * track first, so the next track is random and the current track is not repeated.
     */
    public void setShuffle(boolean enabled) {
        lock.lock();
        try {
            if (enabled == shuffle) {
                return;
            }
            shuffle = enabled;
            playbackOrder.clear();
            shuffleHistory.clear();
            if (enabled) {
                List<String> ids = new ArrayList<>(tracks.size());
                for (Track t : tracks) {
                    ids.add(t.id());
                }
                String currentId = currentId();
                if (currentId != null) {
                    ids.remove(currentId);
                }
                shuffleInPlace(ids);
                if (currentId != null) {
                    playbackOrder.add(currentId);
                }
                playbackOrder.addAll(ids);
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean shuffle() {
        lock.lock();
        try {
            return shuffle;
        } finally {
            lock.unlock();
        }
    }

    public Optional<Track> current() {
        lock.lock();
        try {
            return currentIndex >= 0 ? Optional.of(tracks.get(currentIndex)) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public int currentIndex() {
        lock.lock();
        try {
            return currentIndex;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return tracks.size();
        } finally {
            lock.unlock();
        }
    }

    /** Copy of the user-visible order. */
    public List<Track> snapshot() {
        lock.lock();
        try {
            return List.copyOf(tracks);
        } finally {
            lock.unlock();
        }
    }

    /** Returns the user-visible order and its corresponding state under one lock acquisition. */
    public View snapshotView() {
        lock.lock();
        try {
            return new View(List.copyOf(tracks),
                    new QueueSnapshot(currentIndex, tracks.size(), repeat, shuffle));
        } finally {
            lock.unlock();
        }
    }

    /** Copy of the order in which tracks will be played next (shuffled when shuffle is on). */
    public List<Track> playbackOrder() {
        lock.lock();
        try {
            if (!shuffle) {
                return List.copyOf(tracks);
            }
            List<Track> ordered = new ArrayList<>(tracks.size());
            for (String id : playbackOrder) {
                ordered.add(tracks.get(indexOfId(id)));
            }
            return List.copyOf(ordered);
        } finally {
            lock.unlock();
        }
    }

    public QueueSnapshot snapshotInfo() {
        lock.lock();
        try {
            return new QueueSnapshot(currentIndex, tracks.size(), repeat, shuffle);
        } finally {
            lock.unlock();
        }
    }

    // ----- internal helpers; all callers hold the lock -----

    private Optional<Track> nextShuffled() {
        String cur = currentId();
        if (cur == null) {
            if (playbackOrder.isEmpty()) {
                return Optional.empty();
            }
            currentIndex = indexOfId(playbackOrder.getFirst());
            return Optional.of(tracks.get(currentIndex));
        }
        int nextOrder = playbackOrder.indexOf(cur) + 1;
        if (nextOrder >= playbackOrder.size()) {
            if (repeat != RepeatMode.ALL) {
                return Optional.empty();
            }
            nextOrder = 0;
        }
        shuffleHistory.push(cur);
        currentIndex = indexOfId(playbackOrder.get(nextOrder));
        return Optional.of(tracks.get(currentIndex));
    }

    private Optional<Track> previousShuffled() {
        while (!shuffleHistory.isEmpty()) {
            int idx = indexOfId(shuffleHistory.pop());
            if (idx >= 0) {
                currentIndex = idx;
                return Optional.of(tracks.get(idx));
            }
        }
        if (repeat == RepeatMode.ALL && !playbackOrder.isEmpty()) {
            currentIndex = indexOfId(playbackOrder.getLast());
            return Optional.of(tracks.get(currentIndex));
        }
        return Optional.empty();
    }

    private void shuffleInPlace(List<String> ids) {
        for (int i = ids.size() - 1; i > 0; i--) {
            Collections.swap(ids, i, random.nextInt(i + 1));
        }
    }

    private String currentId() {
        return currentIndex >= 0 ? tracks.get(currentIndex).id() : null;
    }

    private int indexOfId(String id) {
        for (int i = 0; i < tracks.size(); i++) {
            if (tracks.get(i).id().equals(id)) {
                return i;
            }
        }
        return -1;
    }

    private void checkIndex(String operation, int index, int min, int max) {
        if (index < min || index > max) {
            throw new QueueException(operation,
                    "index " + index + " out of range [" + min + ", " + max + "]");
        }
    }
}
