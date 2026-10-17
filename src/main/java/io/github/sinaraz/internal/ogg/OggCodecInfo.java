package io.github.sinaraz.internal.ogg;

import java.util.Objects;

import io.github.sinaraz.model.TrackMetadata;

/** Immutable format/header information for one Ogg logical audio stream. */
record OggCodecInfo(OggHeaderParser.Codec codec, int sampleRate, int channels, int preSkip,
                    int outputGain, int mappingFamily, int streamCount, int coupledStreamCount,
                    short[] channelMapping, TrackMetadata metadata) {

    OggCodecInfo {
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(metadata, "metadata");
        channelMapping = channelMapping == null ? null : channelMapping.clone();
        if (sampleRate <= 0 || channels < 1 || channels > 2) {
            throw new IllegalArgumentException("unsupported canonical format: " + sampleRate + " Hz, "
                    + channels + " channel(s)");
        }
    }

    @Override
    public short[] channelMapping() {
        return channelMapping == null ? null : channelMapping.clone();
    }
}
