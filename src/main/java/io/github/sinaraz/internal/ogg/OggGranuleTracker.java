package io.github.sinaraz.internal.ogg;

import io.github.sinaraz.error.CorruptFileException;

/**
 * Applies codec-specific Ogg granule rules to the PCM produced by one logical stream.
 * Input frame counts are per channel; output adjustments remove only start/end padding, never
 * resample or alter interleaving.
 */
final class OggGranuleTracker {

    record Adjustment(int trimStartFrames, int trimEndFrames, long outputFrames) {}

    private final OggHeaderParser.Codec codec;
    private final int preSkip;
    private long preSkipRemaining;
    private boolean firstAudioPage = true;
    private boolean ended;
    private long previousGranule;
    private long initialOriginFrames;
    private long totalDecodedFrames;
    private long totalOutputFrames;

    OggGranuleTracker(OggHeaderParser.Codec codec, int preSkip) {
        this(codec, preSkip, false);
    }

    OggGranuleTracker(OggHeaderParser.Codec codec, int preSkip, boolean rangeStart) {
        this.codec = codec;
        this.preSkip = preSkip;
        this.preSkipRemaining = rangeStart ? 0 : preSkip;
        this.firstAudioPage = !rangeStart;
    }

    Adjustment apply(long granulePosition, boolean eos, long decodedFrames,
                     long audioPacketCount, long lastPacketFrames) {
        if (ended) {
            throw corrupt("audio packets follow the end of an Ogg logical stream");
        }
        if (decodedFrames < 0 || audioPacketCount < 0 || lastPacketFrames < 0) {
            throw new IllegalArgumentException("PCM counts must not be negative");
        }
        if (audioPacketCount == 0) {
            if (decodedFrames != 0 || lastPacketFrames != 0) {
                throw new IllegalArgumentException("a page without audio packets cannot produce PCM");
            }
            if (eos) {
                if (granulePosition >= 0 && granulePosition < previousGranule) {
                    throw corrupt("EOS granule position precedes the previous audio page");
                }
                ended = true;
            }
            return new Adjustment(0, 0, 0);
        }
        if (granulePosition < 0) {
            throw corrupt("audio packets complete on a page without a granule position");
        }
        if (eos && codec == OggHeaderParser.Codec.OPUS && granulePosition < preSkip) {
            throw corrupt("Opus EOS granule position is smaller than pre-skip");
        }

        long trimStart = 0;
        long trimEnd = 0;
        if (firstAudioPage) {
            long initialPosition = granulePosition - decodedFrames;
            if (initialPosition > 0) {
                initialOriginFrames = initialPosition;
            }
            if (codec == OggHeaderParser.Codec.OPUS) {
                if (!eos && granulePosition < decodedFrames) {
                    throw corrupt("first Opus audio-page granule is smaller than decoded packet samples");
                }
                if (eos && granulePosition < decodedFrames) {
                    trimEnd = decodedFrames - granulePosition;
                }
            } else {
                if (granulePosition > decodedFrames && audioPacketCount != 2) {
                    throw corrupt("Vorbis non-zero initial granule requires exactly two packets on the first audio page");
                }
                if (eos && granulePosition < decodedFrames) {
                    trimEnd = decodedFrames - granulePosition;
                } else if (!eos && granulePosition < decodedFrames) {
                    // Vorbis start trimming is only defined when the first audio page contains two
                    // packets; the decoder must see both before any initial samples are returned.
                    if (audioPacketCount != 2) {
                        throw corrupt("Vorbis initial trimming requires exactly two packets on the first audio page");
                    }
                    trimStart = decodedFrames - granulePosition;
                }
            }
        } else {
            long expected = safeAdd(previousGranule, decodedFrames, "audio granule overflow");
            if (granulePosition < previousGranule) {
                throw corrupt("audio granule position moves backwards");
            }
            if (eos) {
                if (granulePosition < expected) {
                    trimEnd = expected - granulePosition;
                }
            } else if (granulePosition < expected) {
                throw corrupt("non-EOS audio granule is smaller than the decoded sample position");
            }
        }

        if (trimStart > decodedFrames || trimEnd > decodedFrames - trimStart) {
            throw corrupt("granule trimming exceeds decoded audio on the page");
        }
        long skip = codec == OggHeaderParser.Codec.OPUS
                ? Math.min(preSkipRemaining, decodedFrames - trimEnd)
                : trimStart;
        if (skip > Integer.MAX_VALUE || trimEnd > Integer.MAX_VALUE) {
            throw corrupt("granule trim exceeds the supported page size");
        }
        long output = decodedFrames - skip - trimEnd;
        preSkipRemaining -= codec == OggHeaderParser.Codec.OPUS ? skip : 0;
        totalDecodedFrames = safeAdd(totalDecodedFrames, decodedFrames, "decoded sample count overflow");
        totalOutputFrames = safeAdd(totalOutputFrames, output, "playable sample count overflow");
        previousGranule = granulePosition;
        firstAudioPage = false;
        if (eos) {
            ended = true;
        }
        return new Adjustment((int) skip, (int) trimEnd, output);
    }

    long totalOutputFrames() {
        return totalOutputFrames;
    }

    long initialOriginFrames() {
        return initialOriginFrames;
    }

    boolean ended() {
        return ended;
    }

    private static long safeAdd(long left, long right, String message) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw corrupt(message);
        }
    }

    private static CorruptFileException corrupt(String message) {
        return new CorruptFileException("read", message, null, null);
    }
}
