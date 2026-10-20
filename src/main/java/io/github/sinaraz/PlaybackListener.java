package io.github.sinaraz;

import java.util.List;

import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.Track;

/**
 * Receives playback events.
 *
 * <p>Callbacks are delivered on a single dedicated event thread, in event order. They are never
 * invoked while a core playback lock is held, so a slow listener cannot stall audio. A listener
 * that throws is logged and skipped; it does not affect playback or other listeners. Progress
 * snapshots and consecutive queue-change notifications may be coalesced when the event thread falls
 * behind; the latest snapshot in each run is delivered, and intermediate snapshots may be skipped.
 */
public interface PlaybackListener {

    /**
     * Called when the playback state changes, such as from playing to paused.
     *
     * @param snapshot the state after the change
     */
    default void onStateChanged(PlaybackSnapshot snapshot) {}

    /**
     * Called when the current track changes, including when the queue ends and playback stops.
     *
     * @param previous the track that was current before, or {@code null} if none was
     * @param current  the track that is now current, or {@code null} if none is
     */
    default void onTrackChanged(Track previous, Track current) {}

    /**
     * Called once a track's decoder is open and its audio format is known.
     *
     * @param track the track that was loaded
     * @param info  the audio format and length of the track
     */
    default void onTrackLoaded(Track track, AudioInfo info) {}

    /**
     * Called periodically while a track plays. Updates may be skipped when the event thread is busy.
     *
     * @param snapshot the playback position at the time of the update
     */
    default void onProgress(PlaybackSnapshot snapshot) {}

    /**
     * Called when the queue's contents, order, or position changes. Consecutive updates may be
     * coalesced while the event thread is busy; this callback receives the latest consistent view.
     *
     * @param queue      the queue in the order the user sees it
     * @param queueState the current position, size, repeat mode, and shuffle flag
     */
    default void onQueueChanged(List<Track> queue, QueueSnapshot queueState) {}

    /**
     * Called when asynchronously loaded tags or metadata status for one queued track changes.
     * This is a small per-track update; it does not require rebuilding a full queue snapshot.
     *
     * @param track the updated queue entry
     */
    default void onTrackMetadataChanged(Track track) {}

    /**
     * Called when a track plays to its end.
     *
     * @param track the track that finished
     */
    default void onPlaybackCompleted(Track track) {}

    /**
     * Called when playback fails, for example because a file is corrupt or the audio device is lost.
     *
     * @param error what failed and which track was involved
     */
    default void onError(PlaybackError error) {}
}
