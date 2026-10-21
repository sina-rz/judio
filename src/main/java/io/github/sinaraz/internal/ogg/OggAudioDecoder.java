package io.github.sinaraz.internal.ogg;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.LongFunction;
import java.util.function.Supplier;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.error.CorruptFileException;
import io.github.sinaraz.error.DecoderException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.AudioInfo;

/**
 * Pull-based Ogg Opus/Vorbis decoder. Ogg page/packet framing is handled by {@link OggPacketReader};
 * Concentus and JOrbis are isolated behind per-logical-stream packet adapters.
 */
public final class OggAudioDecoder implements AudioDecoder {

    private static final int MAX_REMOTE_SEEK_POINTS = 128;

    private final Path localPath;
    private final OggFileIndex localIndex;
    private final String source;
    private final boolean local;
    private final long remoteByteLength;
    private final LongFunction<InputStream> remoteRangeOpener;

    private final AudioInfo info;
    private OggPacketReader reader;
    private OggStreamDecoder localSession;
    private OggStreamDecoder remoteSession;
    private final Map<Long, OggStreamDecoder> remoteCandidates = new HashMap<>();
    private int localSegmentIndex;
    private long localSegmentBaseFrame;
    private boolean localRangeActive;
    private boolean localReaderRanged;
    private boolean localReopenNextSegment;
    private long remoteFrameCount = -1L;
    private RemoteSeekInfo remoteSeekInfo;
    private OggPageProbe.PageHeader remoteFirstAudioPage;
    private final NavigableMap<Long, OggPageProbe.PageHeader> remoteSeekPoints = new TreeMap<>();
    private long localSessionId = -1L;
    private long remoteSessionId = -1L;
    private long remoteLastEosOffset = -1L;
    private int remoteSerialNumber;
    private long remoteGranuleOffsetFrames;
    private boolean remoteGranuleOffsetReady;
    private boolean remoteRangeActive;
    private long pendingPcmStartFrame = -1L;
    private int remoteSampleRate;
    private int remoteChannels;
    private boolean remoteSelectedEnded;
    private boolean remoteSawSupported;
    private byte[] pendingPcm = new byte[0];
    private int pendingPosition;
    private long framePosition;
    private boolean eof;
    private boolean closed;

    private record RemoteSeekInfo(int serialNumber, long granuleOffsetFrames, OggHeaderParser.Codec codec,
                                  OggPageProbe.PageHeader firstAudioPage, List<OggPage> headers) {
        RemoteSeekInfo {
            headers = List.copyOf(headers);
        }
    }

    private OggAudioDecoder(Path path, OggFileIndex index, InputStream input) throws IOException {
        this.localPath = path;
        this.localIndex = index;
        this.source = path.getFileName() == null ? "local Ogg file" : path.getFileName().toString();
        this.local = true;
        this.remoteByteLength = -1L;
        this.remoteRangeOpener = null;
        this.reader = new OggPacketReader(input, source);
        this.info = makeLocalAudioInfo(index);
    }

    private OggAudioDecoder(InputStream input, String source, long remoteByteLength,
                            LongFunction<InputStream> remoteRangeOpener) throws IOException {
        this.localPath = null;
        this.localIndex = null;
        this.source = source == null ? "Ogg stream" : source;
        this.local = false;
        this.remoteByteLength = remoteByteLength;
        this.remoteRangeOpener = remoteByteLength > 0 ? remoteRangeOpener : null;
        this.reader = new OggPacketReader(input, this.source);
        try {
            initializeRemote();
            OggCodecInfo first = remoteSession.info();
            remoteSeekInfo = new RemoteSeekInfo(remoteSerialNumber, remoteGranuleOffsetFrames, first.codec(),
                    remoteFirstAudioPage, remoteSession.headerPages());
            remoteFrameCount = probeRemoteFrameCount();
            Duration duration = remoteFrameCount >= 0
                    ? durationOf(remoteFrameCount, first.sampleRate()) : Duration.ZERO;
            this.info = new AudioInfo(duration, first.sampleRate(), first.channels(), 16,
                    first.channels() * 2, Math.max(0L, remoteFrameCount), "PCM_SIGNED", ByteOrder.LITTLE_ENDIAN,
                    this.remoteRangeOpener != null,
                    "Ogg " + first.codec(), decoderName(first.codec()));
        } catch (IOException | RuntimeException failure) {
            closeSessions();
            try {
                reader.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            reader = null;
            throw failure;
        }
    }

    /** Opens and indexes a local Ogg file. The scan reads page headers/granules but does not decode audio. */
    public static OggAudioDecoder openLocal(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        OggFileIndex index = OggFileProbe.scan(path);
        InputStream input = Files.newInputStream(path);
        try {
            return new OggAudioDecoder(path, index, input);
        } catch (IOException | RuntimeException e) {
            try {
                input.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /** Opens an incremental Ogg source without a way to reopen it; duration is unknown and seeking is unavailable. */
    public static OggAudioDecoder openRemote(InputStream input, String source) throws IOException {
        return openRemote(input, source, -1L, null);
    }

    /**
     * Compatibility overload for the former reopen-at-zero hook. A start-only opener cannot support
     * random-access seeking, so it is not used; provide a byte length and offset-aware range opener
     * with the four-argument overload instead.
     *
     * @deprecated use the offset-aware overload for seekable remote sources
     */
    @Deprecated
    public static OggAudioDecoder openRemote(InputStream input, String source,
                                             Supplier<InputStream> legacyOpener) throws IOException {
        return openRemote(input, source, -1L, null);
    }

    /**
     * Opens an incremental Ogg source with optional HTTP byte-range access. The initial input begins
     * at byte zero; {@code rangeOpener} must return a fresh input beginning at exactly the requested
     * offset and owns that input until it is closed. A non-positive {@code contentLength} disables
     * random seeking. For a finite, single-logical-stream source, a bounded tail probe supplies
     * granule-based duration without downloading the whole file. Missing/invalid EOS, visible
     * chaining, or a failed optional probe leaves duration unknown without preventing playback.
     */
    public static OggAudioDecoder openRemote(InputStream input, String source, long contentLength,
                                             LongFunction<InputStream> rangeOpener) throws IOException {
        Objects.requireNonNull(input, "input");
        try {
            return new OggAudioDecoder(input, source, contentLength, rangeOpener);
        } catch (IOException | RuntimeException e) {
            try {
                input.close();
            } catch (IOException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    @Override
    public AudioInfo audioInfo() {
        return info;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        ensureOpen();
        Objects.checkFromIndexSize(offset, length, buffer.length);
        int frameSize = info.frameSize();
        if (length < frameSize) {
            throw new IllegalArgumentException("length must hold at least one frame: " + length);
        }
        int want = length - (length % frameSize);
        // A remote tail probe must not stop sequential playback of an unexpected chained stream.
        long frameLimit = local ? knownFrameCount() : -1L;
        if (frameLimit >= 0 && framePosition >= frameLimit) {
            return -1;
        }
        if (frameLimit >= 0) {
            long remaining = frameLimit - framePosition;
            long available = remaining > Long.MAX_VALUE / frameSize
                    ? Long.MAX_VALUE
                    : remaining * frameSize;
            want = (int) Math.min(want, available);
        }

        int written = 0;
        while (written < want) {
            if (!ensurePendingPcm()) {
                break;
            }
            int available = pendingPcm.length - pendingPosition;
            int count = Math.min(want - written, available);
            count -= count % frameSize;
            if (count == 0) {
                throw new CorruptFileException("read", "Ogg decoder produced a partial PCM frame", localPath, null);
            }
            System.arraycopy(pendingPcm, pendingPosition, buffer, offset + written, count);
            pendingPosition += count;
            written += count;
            framePosition += count / frameSize;
        }
        if (written == 0) {
            return -1;
        }
        return written;
    }

    @Override
    public void seek(Duration position) throws IOException {
        ensureOpen();
        Objects.requireNonNull(position, "position");
        if (!isSeekable()) {
            throw new SeekNotSupportedException("seek",
                    "this remote Ogg source does not provide HTTP byte-range access", null);
        }
        long target = framesAt(position, info.sampleRate());
        long frameLimit = knownFrameCount();
        if (frameLimit >= 0) {
            target = Math.min(target, frameLimit);
        }
        if (target == framePosition) {
            return;
        }
        if (frameLimit >= 0 && target == frameLimit) {
            // Seeking to/past a known end needs no reopen, range requests or decoding.
            pendingPcm = new byte[0];
            pendingPosition = 0;
            pendingPcmStartFrame = -1L;
            framePosition = target;
            eof = true;
            return;
        }
        if (target > framePosition
                && target - framePosition <= (pendingPcm.length - pendingPosition) / info.frameSize()) {
            pendingPosition += Math.toIntExact((target - framePosition) * info.frameSize());
            framePosition = target;
            return;
        }
        if (local) {
            resetLocalReader(target);
        } else {
            resetRemoteReader(target);
        }
        discardTo(target);
        if (frameLimit >= 0 && framePosition < target) {
            throw new CorruptFileException("seek", "Ogg stream ended before the indexed seek target",
                    localPath, null);
        }
    }

    /** Discards whole frames directly from page buffers, without allocating/copying scratch PCM. */
    private void discardTo(long target) throws IOException {
        while (framePosition < target && ensurePendingPcm()) {
            if (framePosition > target) {
                throw new CorruptFileException("seek", "Ogg seek anchor is after the requested frame",
                        localPath, null);
            }
            long available = (pendingPcm.length - pendingPosition) / info.frameSize();
            long skip = Math.min(target - framePosition, available);
            pendingPosition += Math.toIntExact(skip * info.frameSize());
            framePosition += skip;
        }
    }

    private boolean ensurePendingPcm() throws IOException {
        if (pendingPosition < pendingPcm.length) {
            return true;
        }
        pendingPcm = nextPcmPage();
        pendingPosition = 0;
        if (pendingPcm == null || pendingPcm.length == 0) {
            pendingPcm = new byte[0];
            return false;
        }
        if (pendingPcmStartFrame >= 0) {
            framePosition = pendingPcmStartFrame;
            pendingPcmStartFrame = -1L;
        }
        return true;
    }

    @Override
    public boolean isSeekable() {
        return local || remoteRangeOpener != null;
    }

    @Override
    public long framePosition() {
        return framePosition;
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            closeSessions();
            pendingPcm = new byte[0];
            pendingPosition = 0;
            if (reader != null) {
                reader.close();
                reader = null;
            }
        }
    }

    private void initializeRemote() throws IOException {
        while ((remoteSampleRate == 0 || remoteSession == null || !remoteSession.hasAudio()) && !eof) {
            byte[] pagePcm = nextRemotePcmPage();
            if (pagePcm != null && pagePcm.length > 0) {
                pendingPcm = pagePcm;
                pendingPosition = 0;
            }
        }
        if (remoteSampleRate == 0 || remoteSession == null || !remoteSession.hasAudio()) {
            if (remoteSawSupported) {
                throw new CorruptFileException("open", "Ogg stream ended before the first audio page", null, null);
            }
            throw new UnsupportedAudioFormatException("open",
                    "Ogg stream contains no supported Opus or Vorbis audio stream", null, null);
        }
    }

    private byte[] nextPcmPage() throws IOException {
        if (eof) {
            return null;
        }
        return local ? nextLocalPcmPage() : nextRemotePcmPage();
    }

    private byte[] nextLocalPcmPage() throws IOException {
        while (!eof) {
            if (localSegmentIndex >= localIndex.segments().size()) {
                eof = true;
                return null;
            }
            if (localReopenNextSegment) {
                OggFileIndex.Segment next = localIndex.segments().get(localSegmentIndex);
                OggPacketReader replacement = new OggPacketReader(openLocalAt(next.bosOffset()),
                        source, next.bosOffset(), next.serialNumber());
                OggPacketReader old = reader;
                reader = replacement;
                localReaderRanged = true;
                localReopenNextSegment = false;
                old.close();
            }
            OggPage page = reader.readPage();
            if (page == null) {
                OggFileIndex.Segment expected = localIndex.segments().get(localSegmentIndex);
                if (expected.exactDuration()) {
                    throw new CorruptFileException("read", "indexed Ogg logical stream is missing its EOS page",
                            localPath, null);
                }
                eof = true;
                return null;
            }
            OggFileIndex.Segment segment = localIndex.segments().get(localSegmentIndex);
            if (page.serialNumber() != segment.serialNumber() || page.byteOffset() < segment.bosOffset()) {
                continue;
            }
            if (localSession == null) {
                localSession = new OggStreamDecoder(source);
                localSessionId = page.streamId();
            }
            if (localSessionId != page.streamId()) {
                throw new CorruptFileException("read", "Ogg logical-stream identity changed unexpectedly",
                        localPath, null);
            }
            byte[] pcm = localSession.processPage(page);
            if (localRangeActive && pcm.length > 0) {
                pcm = positionRangePage(page, pcm, segment.granuleOffsetFrames(), localSegmentBaseFrame,
                        segment.codecInfo().channels());
            }
            if (page.endOfStream()) {
                if (!localRangeActive && segment.exactDuration()
                        && localSession.outputFrames() != segment.frameCount()) {
                    throw new CorruptFileException("read",
                            "decoded Ogg sample count " + localSession.outputFrames()
                                    + " differs from indexed count " + segment.frameCount(),
                            localPath, null);
                }
                localSession.close();
                localSession = null;
                localSessionId = -1L;
                localSegmentIndex++;
                localSegmentBaseFrame += segment.frameCount();
                localReopenNextSegment = localReaderRanged;
                localRangeActive = false;
            }
            if (pcm.length > 0) {
                return pcm;
            }
        }
        return null;
    }

    private byte[] nextRemotePcmPage() throws IOException {
        while (!eof) {
            if (remoteSession != null && remoteSelectedEnded) {
                remoteSession.close();
                remoteSession = null;
                remoteSessionId = -1L;
                remoteSelectedEnded = false;
                clearRemoteCandidates();
            }

            OggPage page = reader.readPage();
            if (page == null) {
                eof = true;
                if (remoteSession != null && !remoteSession.headersReady()) {
                    throw new CorruptFileException("read", "truncated Ogg codec headers", null, null);
                }
                clearRemoteCandidates();
                return null;
            }

            if (remoteSession != null) {
                if (page.streamId() != remoteSessionId) {
                    continue;
                }
                byte[] pcm = remoteSession.processPage(page);
                if (page.endOfStream()) {
                    remoteSelectedEnded = true;
                    remoteLastEosOffset = page.byteOffset();
                }
                if (remoteSession.headersReady() && remoteSampleRate == 0) {
                    acceptRemoteFormat(remoteSession.info());
                }
                updateRemoteGranuleOffset(page, remoteSession);
                if (remoteRangeActive && pcm.length > 0) {
                    pcm = positionRangePage(page, pcm, remoteGranuleOffsetFrames, 0L,
                            remoteSession.info().channels());
                }
                if (pcm.length > 0) {
                    return pcm;
                }
                continue;
            }

            if (page.beginningOfStream() && page.byteOffset() > remoteLastEosOffset) {
                remoteCandidates.put(page.streamId(), new OggStreamDecoder(source));
            }
            OggStreamDecoder candidate = remoteCandidates.get(page.streamId());
            if (candidate == null) {
                continue;
            }
            byte[] pcm = candidate.processPage(page);
            if (candidate.supported()) {
                remoteSawSupported = true;
                remoteSession = candidate;
                remoteSessionId = page.streamId();
                remoteSerialNumber = page.serialNumber();
                remoteCandidates.remove(page.streamId());
                clearRemoteCandidates();
                if (candidate.headersReady()) {
                    acceptRemoteFormat(candidate.info());
                }
                updateRemoteGranuleOffset(page, candidate);
                if (page.endOfStream()) {
                    remoteSelectedEnded = true;
                    remoteLastEosOffset = page.byteOffset();
                }
                if (pcm.length > 0) {
                    return pcm;
                }
                continue;
            }
            if (page.endOfStream()) {
                candidate.close();
                remoteCandidates.remove(page.streamId());
            }
        }
        return null;
    }

    private void acceptRemoteFormat(OggCodecInfo candidate) {
        if (remoteSampleRate == 0) {
            remoteSampleRate = candidate.sampleRate();
            remoteChannels = candidate.channels();
            return;
        }
        if (candidate.sampleRate() != remoteSampleRate || candidate.channels() != remoteChannels) {
            throw new UnsupportedAudioFormatException("read",
                    "chained Ogg stream changes sample rate or channel count", null, null);
        }
    }

    private void updateRemoteGranuleOffset(OggPage page, OggStreamDecoder session) {
        if (remoteGranuleOffsetReady && session.hasAudio()) {
            return;
        }
        if (!session.hasAudio()) {
            return;
        }
        OggCodecInfo codecInfo = session.info();
        long codecOffset = codecInfo.codec() == OggHeaderParser.Codec.OPUS ? codecInfo.preSkip() : 0L;
        try {
            remoteGranuleOffsetFrames = Math.addExact(codecOffset, session.initialOriginFrames());
        } catch (ArithmeticException e) {
            throw new CorruptFileException("open", "Ogg granule origin overflows the supported range", null, e);
        }
        remoteGranuleOffsetReady = true;
        remoteFirstAudioPage = new OggPageProbe.PageHeader(page.byteOffset(),
                Math.toIntExact(reader.byteOffset() - page.byteOffset()), page.serialNumber(),
                page.sequenceNumber(), page.granulePosition(), page.continued(),
                page.beginningOfStream(), page.endOfStream());
    }

    /** Maps cold-start page output back onto the absolute timeline, including unusually large Opus pre-skip. */
    private byte[] positionRangePage(OggPage page, byte[] pcm, long granuleOffset, long baseFrame, int channels) {
        int frameSize = channels * 2;
        long endFrame = page.granulePosition() - granuleOffset;
        long startFrame = endFrame - pcm.length / frameSize;
        if (startFrame < 0) {
            long trimFrames = Math.min(-startFrame, pcm.length / frameSize);
            pcm = Arrays.copyOfRange(pcm, Math.toIntExact(trimFrames * frameSize), pcm.length);
            startFrame += trimFrames;
        }
        pendingPcmStartFrame = baseFrame + Math.max(0L, startFrame);
        return pcm;
    }

    private long probeRemoteFrameCount() {
        if (remoteFirstAudioPage == null) {
            return -1L;
        }
        try {
            OggPageProbe.PageHeader eosPage;
            if (remoteFirstAudioPage.endOfStream() && remoteFirstAudioPage.byteOffset()
                    + remoteFirstAudioPage.pageLength() == remoteByteLength) {
                // Very short files can already have a fully validated EOS in the startup PCM.
                // A known byte length proves there is no later chain, even without HTTP ranges.
                eosPage = remoteFirstAudioPage;
            } else if (remoteRangeOpener != null) {
                eosPage = OggPageProbe.findLastEos(remoteRangeOpener, remoteByteLength,
                        remoteSeekInfo.serialNumber(), remoteFirstAudioPage.byteOffset());
            } else {
                return -1L;
            }
            if (eosPage == null || eosPage.sequenceNumber() < remoteFirstAudioPage.sequenceNumber()
                    || eosPage.byteOffset() < remoteFirstAudioPage.byteOffset()
                    || eosPage.granulePosition() < remoteFirstAudioPage.granulePosition()
                    || eosPage.granulePosition() < remoteSeekInfo.granuleOffsetFrames()) {
                return -1L;
            }
            return eosPage.granulePosition() - remoteSeekInfo.granuleOffsetFrames();
        } catch (IOException | RuntimeException ignored) {
            // Optional metadata must never make an otherwise playable stream fail to open. In
            // particular, some servers advertise ranges but reject the actual tail request.
            return -1L;
        }
    }

    private void resetLocalReader(long targetFrame) throws IOException {
        int segmentIndex = 0;
        long baseFrame = 0L;
        while (segmentIndex + 1 < localIndex.segments().size()) {
            OggFileIndex.Segment segment = localIndex.segments().get(segmentIndex);
            if (!segment.exactDuration() || targetFrame - baseFrame < segment.frameCount()) {
                break;
            }
            baseFrame += segment.frameCount();
            segmentIndex++;
        }
        OggFileIndex.Segment segment = localIndex.segments().get(segmentIndex);
        long granuleTarget = Math.max(0L, saturatingAdd(targetFrame - baseFrame,
                segment.granuleOffsetFrames()) - prerollFrames(segment.codecInfo().codec()));
        OggFileIndex.SeekPoint anchor = segment.atOrBefore(granuleTarget);
        long offset = anchor == null ? segment.bosOffset() : anchor.byteOffset();
        OggStreamDecoder session = anchor == null ? null : new OggStreamDecoder(source, segment.headers());
        if (session != null) {
            session.prepareForRangeStart(anchor.firstAudioPage());
        }
        OggPacketReader replacement;
        try {
            InputStream input = openLocalAt(offset);
            replacement = new OggPacketReader(input, source, offset, segment.serialNumber());
        } catch (IOException | RuntimeException failure) {
            if (session != null) {
                session.close();
            }
            throw failure;
        }
        OggPacketReader old = reader;
        closeSessions();
        reader = replacement;
        localSession = session;
        localSessionId = anchor == null ? -1L : 0L;
        localSegmentIndex = segmentIndex;
        localSegmentBaseFrame = baseFrame;
        localRangeActive = anchor != null;
        localReaderRanged = true;
        localReopenNextSegment = false;
        clearPending(baseFrame);
        if (old != null) {
            old.close();
        }
    }

    private InputStream openLocalAt(long offset) throws IOException {
        SeekableByteChannel channel = Files.newByteChannel(localPath);
        try {
            channel.position(offset);
            return new BufferedInputStream(Channels.newInputStream(channel), 64 * 1024);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private void resetRemoteReader(long targetFrame) throws IOException {
        // Without a trustworthy single-stream end, replay is the safe fallback: granules reset
        // across chained streams and cannot be treated as one globally increasing seek index.
        if (targetFrame == 0 || remoteFrameCount < 0 || remoteSeekInfo == null) {
            resetRemoteFromStart();
            return;
        }
        RemoteSeekInfo seek = remoteSeekInfo;
        long granuleTarget = Math.max(0L, saturatingAdd(targetFrame, seek.granuleOffsetFrames())
                - prerollFrames(seek.codec()));
        OggPageProbe.PageHeader anchor = null;
        Map.Entry<Long, OggPageProbe.PageHeader> cached = remoteSeekPoints.floorEntry(granuleTarget);
        if (cached != null && granuleTarget - cached.getKey() <= info.sampleRate() * 2L) {
            anchor = cached.getValue();
        }
        if (anchor == null) {
            anchor = OggPageProbe.findPageAtOrBefore(remoteRangeOpener, remoteByteLength,
                    seek.serialNumber(), granuleTarget);
            if (anchor != null) {
                remoteSeekPoints.put(anchor.granulePosition(), anchor);
                if (remoteSeekPoints.size() > MAX_REMOTE_SEEK_POINTS) {
                    remoteSeekPoints.pollFirstEntry();
                }
            }
        }
        OggPageProbe.PageHeader first = seek.firstAudioPage();
        if (anchor == null || anchor.byteOffset() < first.byteOffset()) {
            anchor = first;
        }
        if (anchor.continued() || anchor.byteOffset() <= 0) {
            resetRemoteFromStart();
            return;
        }
        OggStreamDecoder session = new OggStreamDecoder(source, seek.headers());
        session.prepareForRangeStart(anchor.byteOffset() == first.byteOffset());
        OggPacketReader replacement;
        try {
            InputStream input = remoteRangeOpener.apply(anchor.byteOffset());
            if (input == null) {
                throw new IOException("remote Ogg range opener returned no input stream");
            }
            replacement = new OggPacketReader(input, source, anchor.byteOffset(), seek.serialNumber());
        } catch (RuntimeException | IOException failure) {
            session.close();
            throw failure;
        }
        // Build first, swap second: a failed range probe/open leaves the old decoder usable.
        OggPacketReader old = reader;
        closeSessions();
        reader = replacement;
        remoteSession = session;
        remoteSessionId = 0L;
        remoteSerialNumber = seek.serialNumber();
        remoteGranuleOffsetFrames = seek.granuleOffsetFrames();
        remoteGranuleOffsetReady = true;
        remoteSampleRate = info.sampleRate();
        remoteChannels = info.channels();
        remoteSelectedEnded = false;
        remoteSawSupported = true;
        remoteLastEosOffset = -1L;
        remoteRangeActive = true;
        clearPending(0L);
        if (old != null) {
            old.close();
        }
    }

    private static int prerollFrames(OggHeaderParser.Codec codec) {
        // Opus requires at least 80 ms at 48 kHz; 240 ms also lets prediction state settle on
        // streams with very small pages. Vorbis needs overlap/block warmup.
        return codec == OggHeaderParser.Codec.OPUS ? 11_520 : 16_384;
    }

    private void resetRemoteFromStart() throws IOException {
        InputStream input = remoteRangeOpener.apply(0L);
        if (input == null) {
            throw new IOException("remote Ogg range opener returned no input stream");
        }
        OggPacketReader replacement = new OggPacketReader(input, source);
        OggPacketReader old = reader;
        closeSessions();
        reader = replacement;
        remoteSessionId = -1L;
        remoteLastEosOffset = -1L;
        remoteSampleRate = 0;
        remoteChannels = 0;
        remoteSelectedEnded = false;
        remoteSawSupported = false;
        remoteSerialNumber = 0;
        remoteGranuleOffsetFrames = 0L;
        remoteGranuleOffsetReady = false;
        remoteRangeActive = false;
        clearPending(0L);
        if (old != null) {
            old.close();
        }
        initializeRemote();
        if (remoteSampleRate != info.sampleRate() || remoteChannels != info.channels()) {
            throw new UnsupportedAudioFormatException("seek",
                    "remote Ogg format changed while reopening the stream", null, null);
        }
    }

    private void clearPending(long frame) {
        pendingPcm = new byte[0];
        pendingPosition = 0;
        pendingPcmStartFrame = -1L;
        framePosition = frame;
        eof = false;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private void closeSessions() {
        if (localSession != null) {
            localSession.close();
            localSession = null;
        }
        if (remoteSession != null) {
            remoteSession.close();
            remoteSession = null;
        }
        clearRemoteCandidates();
    }

    private void clearRemoteCandidates() {
        for (OggStreamDecoder candidate : remoteCandidates.values()) {
            candidate.close();
        }
        remoteCandidates.clear();
    }

    private long knownFrameCount() {
        return local ? (localIndex.exactDuration() ? localIndex.frameCount() : -1L) : remoteFrameCount;
    }

    private AudioInfo makeLocalAudioInfo(OggFileIndex index) {
        Duration duration = index.exactDuration() ? durationOf(index.frameCount(), index.sampleRate()) : Duration.ZERO;
        String sourceFormat = index.segments().size() == 1
                ? "Ogg " + index.segments().get(0).codecInfo().codec()
                : "Ogg chained audio";
        String decoder = index.segments().size() == 1
                ? decoderName(index.segments().get(0).codecInfo().codec())
                : "Concentus/JOrbis";
        return new AudioInfo(duration, index.sampleRate(), index.channels(), 16,
                index.channels() * 2, index.exactDuration() ? index.frameCount() : 0L,
                "PCM_SIGNED", ByteOrder.LITTLE_ENDIAN, true, sourceFormat, decoder);
    }

    private static String decoderName(OggHeaderParser.Codec codec) {
        return switch (codec) {
            case OPUS -> "Concentus";
            case VORBIS -> "JOrbis";
            case UNKNOWN -> "Ogg codec";
        };
    }

    private static Duration durationOf(long frames, int sampleRate) {
        long seconds = frames / sampleRate;
        long remainder = frames % sampleRate;
        long nanos = Math.round(remainder * 1_000_000_000.0 / sampleRate);
        if (nanos == 1_000_000_000L) {
            seconds++;
            nanos = 0;
        }
        return Duration.ofSeconds(seconds, nanos);
    }

    private static long framesAt(Duration position, int sampleRate) {
        if (position.isNegative() || position.isZero()) {
            return 0L;
        }
        long seconds = position.getSeconds();
        long whole;
        try {
            whole = Math.multiplyExact(seconds, sampleRate);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
        long fractional = Math.round(position.getNano() * (double) sampleRate / 1_000_000_000.0);
        try {
            return Math.addExact(whole, fractional);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new DecoderException("read", "decoder is closed", localPath, null);
        }
    }
}
