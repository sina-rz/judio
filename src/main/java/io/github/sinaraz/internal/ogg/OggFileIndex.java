package io.github.sinaraz.internal.ogg;

import java.util.List;

/** Immutable scan result for a local Ogg file, including sparse, packet-aligned seek points. */
record OggFileIndex(List<Segment> segments, int sampleRate, int channels,
                    long frameCount, boolean exactDuration) {

    OggFileIndex {
        segments = List.copyOf(segments);
    }

    record Segment(long streamId, OggCodecInfo codecInfo, long frameCount, boolean exactDuration,
                   long bosOffset, int serialNumber, long granuleOffsetFrames,
                   List<OggPage> headers, List<SeekPoint> seekPoints) {
        Segment {
            headers = List.copyOf(headers);
            seekPoints = List.copyOf(seekPoints);
        }

        /** Last page whose end granule leaves enough room to warm up the codec before the target. */
        SeekPoint atOrBefore(long granuleTarget) {
            int low = 0;
            int high = seekPoints.size();
            while (low < high) {
                int middle = (low + high) >>> 1;
                if (seekPoints.get(middle).granulePosition() <= granuleTarget) {
                    low = middle + 1;
                } else {
                    high = middle;
                }
            }
            return low == 0 ? null : seekPoints.get(low - 1);
        }
    }

    record SeekPoint(long byteOffset, long granulePosition, boolean firstAudioPage) {}
}
