package io.github.sinaraz.internal.ogg;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.sinaraz.error.CorruptFileException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.TrackMetadata;

/** Scans a local Ogg file for codec headers, chain boundaries, and granule-derived duration. */
final class OggFileProbe {


    static OggFileIndex scan(Path path) throws IOException {
        String source = path.getFileName() == null ? "local Ogg file" : path.getFileName().toString();
        List<OggFileIndex.Segment> segments = new ArrayList<>();
        Map<Long, ProbeStream> streams = new HashMap<>();
        ProbeStream selected = null;
        long lastSelectedEosOffset = -1L;
        int firstRate = 0;
        int firstChannels = 0;
        long totalFrames = 0L;
        boolean exact = true;
        boolean sawRecognizedCodec = false;

        try {
            try (OggPacketReader reader = new OggPacketReader(Files.newInputStream(path), source)) {
                OggPage page;
                while ((page = reader.readPage()) != null) {
                    ProbeStream state;
                    if (selected != null && selected.streamId == page.streamId()) {
                        state = selected;
                    } else if (selected == null && page.beginningOfStream()) {
                        state = new ProbeStream(page.streamId(), page.byteOffset(), source);
                        streams.put(page.streamId(), state);
                    } else if (selected == null) {
                        state = streams.get(page.streamId());
                        if (state == null) {
                            continue;
                        }
                    } else {
                        // Other logical streams are demultiplexed and validated by OggPacketReader, but
                        // audio tracks that begin while the selected stream is active are not mistaken
                        // for a chained continuation.
                        continue;
                    }

                    PageCount count = state.accept(page);
                    if (state.headers.codec() != null && state.headers.codec() != OggHeaderParser.Codec.UNKNOWN) {
                        sawRecognizedCodec = true;
                    }
                    if (!state.selected && state.headers.codec() != null
                            && state.headers.codec() != OggHeaderParser.Codec.UNKNOWN) {
                        if (selected == null && state.bosOffset > lastSelectedEosOffset) {
                            selected = state;
                            state.selected = true;
                        }
                    }
                    if (state == selected && count.audioPackets() > 0) {
                        state.timing.apply(page.granulePosition(), page.endOfStream(), count.decodedFrames(),
                                count.audioPackets(), count.lastPacketFrames());
                        state.addSeekPoint(page);
                        state.hasAudio = true;
                    } else if (state == selected && page.endOfStream()) {
                        state.timing.apply(page.granulePosition(), true, 0, 0, 0);
                    }

                    if (state != selected && page.endOfStream()) {
                        streams.remove(state.streamId);
                        state.close();
                    }
                    if (state == selected && page.endOfStream()) {
                        if (!state.headers.ready()) {
                            throw corrupt("Ogg audio stream ended before its codec headers were complete", source);
                        }
                        if (!state.hasAudio) {
                            throw corrupt("Ogg audio stream contains no audio packets", source);
                        }
                        OggCodecInfo codecInfo = state.headers.info();
                        if (firstRate == 0) {
                            firstRate = codecInfo.sampleRate();
                            firstChannels = codecInfo.channels();
                        } else if (codecInfo.sampleRate() != firstRate || codecInfo.channels() != firstChannels) {
                            throw new UnsupportedAudioFormatException("open",
                                    "chained Ogg streams change sample rate or channel count", path, null);
                        }
                        long segmentFrames = state.timing.totalOutputFrames();
                        segments.add(state.segment(segmentFrames, true));
                        totalFrames = addFrames(totalFrames, segmentFrames, source);
                        lastSelectedEosOffset = page.byteOffset();
                        streams.remove(state.streamId);
                        state.close();
                        selected = null;
                    }
                }
            }

            if (selected != null) {
                if (!selected.headers.ready()) {
                    throw corrupt("truncated Ogg audio stream headers", source);
                }
                if (!selected.hasAudio) {
                    throw corrupt("Ogg audio stream contains no complete audio packets", source);
                }
                OggCodecInfo codecInfo = selected.headers.info();
                if (firstRate == 0) {
                    firstRate = codecInfo.sampleRate();
                    firstChannels = codecInfo.channels();
                } else if (codecInfo.sampleRate() != firstRate || codecInfo.channels() != firstChannels) {
                    throw new UnsupportedAudioFormatException("open",
                            "chained Ogg streams change sample rate or channel count", path, null);
                }
                segments.add(selected.segment(0L, false));
                exact = false;
            }
            if (segments.isEmpty()) {
                String message = sawRecognizedCodec
                        ? "Ogg file has no complete playable audio stream"
                        : "Ogg file contains no supported Opus or Vorbis audio stream";
                throw new UnsupportedAudioFormatException("open", message, path, null);
            }
            if (!exact) {
                totalFrames = 0L;
            }
            return new OggFileIndex(segments, firstRate, firstChannels, totalFrames, exact);
        } finally {
            for (ProbeStream stream : streams.values()) {
                stream.close();
            }
            streams.clear();
        }
    }

    static TrackMetadata readMetadata(Path path) throws IOException {
        String source = path.getFileName() == null ? "local Ogg file" : path.getFileName().toString();
        Map<Long, OggHeaderParser> parsers = new HashMap<>();
        try {
            try (OggPacketReader reader = new OggPacketReader(Files.newInputStream(path), source)) {
                OggPage page;
                while ((page = reader.readPage()) != null) {
                    OggHeaderParser parser = parsers.get(page.streamId());
                    if (parser == null && page.beginningOfStream()) {
                        parser = new OggHeaderParser(source);
                        parsers.put(page.streamId(), parser);
                    }
                    if (parser == null) {
                        continue;
                    }
                    for (OggPacket packet : page.packets()) {
                        OggHeaderParser.PacketRole role = parser.accept(page, packet);
                        if (role != OggHeaderParser.PacketRole.UNSUPPORTED && parser.ready()) {
                            return parser.info().metadata();
                        }
                    }
                    if (page.endOfStream()) {
                        OggHeaderParser removed = parsers.remove(page.streamId());
                        if (removed != null) {
                            removed.close();
                        }
                    }
                }
            }
            return TrackMetadata.EMPTY;
        } finally {
            closeParsers(parsers);
        }
    }

    static Optional<CoverArt> readCoverArt(Path path) throws IOException {
        String source = path.getFileName() == null ? "local Ogg file" : path.getFileName().toString();
        Map<Long, OggHeaderParser> parsers = new HashMap<>();
        try {
            try (OggPacketReader reader = new OggPacketReader(Files.newInputStream(path), source)) {
                OggPage page;
                while ((page = reader.readPage()) != null) {
                    OggHeaderParser parser = parsers.get(page.streamId());
                    if (parser == null && page.beginningOfStream()) {
                        parser = new OggHeaderParser(source);
                        parsers.put(page.streamId(), parser);
                    }
                    if (parser == null) {
                        continue;
                    }
                    for (OggPacket packet : page.packets()) {
                        OggHeaderParser.PacketRole role = parser.accept(page, packet);
                        if (role == OggHeaderParser.PacketRole.HEADER
                                && packet.packetNumber() == 1
                                && parser.codec() != OggHeaderParser.Codec.UNKNOWN) {
                            Optional<CoverArt> art = OggPictureParser.fromTags(parser.codec(), packet.data());
                            if (art.isPresent()) {
                                return art;
                            }
                        }
                        if (role != OggHeaderParser.PacketRole.UNSUPPORTED && parser.ready()) {
                            return Optional.empty();
                        }
                    }
                    if (page.endOfStream()) {
                        OggHeaderParser removed = parsers.remove(page.streamId());
                        if (removed != null) {
                            removed.close();
                        }
                    }
                }
            }
            return Optional.empty();
        } finally {
            closeParsers(parsers);
        }
    }

    private static void closeParsers(Map<Long, OggHeaderParser> parsers) {
        for (OggHeaderParser parser : parsers.values()) {
            parser.close();
        }
        parsers.clear();
    }

    private static long addFrames(long left, long right, String source) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            throw new CorruptFileException("open", "Ogg playable sample count overflow (" + source + ")", null, e);
        }
    }

    private static CorruptFileException corrupt(String message, String source) {
        return new CorruptFileException("open", message + " (" + source + ")", null, null);
    }

    private record PageCount(long decodedFrames, long audioPackets, long lastPacketFrames) {}

    private static final class ProbeStream {
        private final long streamId;
        private final long bosOffset;
        private final OggHeaderParser headers;
        private final List<OggFileIndex.SeekPoint> seekPoints = new ArrayList<>();
        private int serialNumber;
        private boolean selected;
        private boolean hasAudio;
        private long previousVorbisBlockSize = -1L;
        private OggGranuleTracker timing;

        private ProbeStream(long streamId, long bosOffset, String source) {
            this.streamId = streamId;
            this.bosOffset = bosOffset;
            this.headers = new OggHeaderParser(source);
        }

        private PageCount accept(OggPage page) {
            serialNumber = page.serialNumber();
            long decodedFrames = 0;
            long audioPackets = 0;
            long lastPacketFrames = 0;
            for (OggPacket packet : page.packets()) {
                OggHeaderParser.PacketRole role = headers.accept(page, packet);
                if (role != OggHeaderParser.PacketRole.AUDIO) {
                    if (headers.ready() && timing == null) {
                        timing = new OggGranuleTracker(headers.codec(), headers.info().preSkip());
                    }
                    continue;
                }
                if (timing == null) {
                    timing = new OggGranuleTracker(headers.codec(), headers.info().preSkip());
                }
                long packetFrames;
                if (headers.codec() == OggHeaderParser.Codec.OPUS) {
                    packetFrames = OggPacketDuration.opusSamples(packet.data());
                } else if (headers.codec() == OggHeaderParser.Codec.VORBIS) {
                    int blockSize = OggPacketDuration.vorbisBlockSize(headers.vorbisInfo(), packet);
                    packetFrames = previousVorbisBlockSize < 0
                            ? 0
                            : (previousVorbisBlockSize + blockSize) / 4;
                    previousVorbisBlockSize = blockSize;
                } else {
                    throw new AssertionError("audio packet in unsupported stream");
                }
                decodedFrames = addFrames(decodedFrames, packetFrames, "Ogg page sample count");
                lastPacketFrames = packetFrames;
                audioPackets++;
            }
            if (page.endOfStream() && headers.codec() != null
                    && headers.codec() != OggHeaderParser.Codec.UNKNOWN && !headers.ready()) {
                throw corrupt("Ogg logical stream ended before its headers completed", "Ogg stream");
            }
            return new PageCount(decodedFrames, audioPackets, lastPacketFrames);
        }

        private void addSeekPoint(OggPage page) {
            // A continued page cannot reconstruct its first packet after a random access. EOS
            // pages need the previous granule for end trimming and are not safe cold starts either.
            if (page.continued() || page.beginningOfStream() || page.endOfStream()) {
                return;
            }
            // At most about one entry per second; do not retain every 20 ms packet/page.
            if (seekPoints.isEmpty() || page.granulePosition()
                    - seekPoints.getLast().granulePosition() >= headers.info().sampleRate()) {
                seekPoints.add(new OggFileIndex.SeekPoint(page.byteOffset(), page.granulePosition(), !hasAudio));
            }
        }

        private OggFileIndex.Segment segment(long frames, boolean exactDuration) {
            OggCodecInfo info = headers.info();
            long codecOffset = info.codec() == OggHeaderParser.Codec.OPUS ? info.preSkip() : 0L;
            long granuleOffset = addFrames(timing.initialOriginFrames(), codecOffset, "Ogg granule origin");
            return new OggFileIndex.Segment(streamId, info, frames, exactDuration,
                    bosOffset, serialNumber, granuleOffset, headers.headerPages(), seekPoints);
        }

        private void close() {
            headers.close();
            timing = null;
        }
    }
}
