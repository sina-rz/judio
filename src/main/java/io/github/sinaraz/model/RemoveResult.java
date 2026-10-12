package io.github.sinaraz.model;


/**
 * Outcome of removing one entry from the queue.
 *
 * @param removed        the removed entry
 * @param currentRemoved whether the removed entry was the current one
 * @param newCurrent     the entry that is current afterwards, or {@code null} if the queue is now empty
 */
public record RemoveResult(Track removed, boolean currentRemoved, Track newCurrent) {}
