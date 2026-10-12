package io.github.sinaraz.model;


/**
 * Queue-level state included in every {@link io.github.sinaraz.model.PlaybackSnapshot}.
 *
 * @param currentIndex index of the current entry, or {@code -1}
 * @param size         number of entries
 * @param repeat       repeat mode
 * @param shuffle      whether shuffle is enabled
 */
public record QueueSnapshot(int currentIndex, int size, RepeatMode repeat, boolean shuffle) {

    public static final QueueSnapshot EMPTY = new QueueSnapshot(-1, 0, RepeatMode.OFF, false);
}
