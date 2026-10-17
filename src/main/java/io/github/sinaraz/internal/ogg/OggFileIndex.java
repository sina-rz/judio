package io.github.sinaraz.internal.ogg;

import java.util.List;

/** Immutable scan result for a local Ogg file. */
record OggFileIndex(List<Segment> segments, int sampleRate, int channels,
                    long frameCount, boolean exactDuration) {

    OggFileIndex {
        segments = List.copyOf(segments);
    }

    record Segment(long streamId, OggCodecInfo codecInfo, long frameCount, boolean exactDuration) {}
}
