package io.github.sinaraz.model;

/** Progress of asynchronous tag loading for a {@link Track}. */
public enum TrackMetadataStatus {
    /** Tags have not been read yet; the track shows file-name based information. */
    PENDING,
    /** Tags were read successfully (missing tags are normal and still count as loaded). */
    LOADED,
    /** Tag reading failed. Playback is still possible. */
    FAILED,
    /** Tags are not read for this kind of source, such as a remote stream. */
    NOT_APPLICABLE
}
