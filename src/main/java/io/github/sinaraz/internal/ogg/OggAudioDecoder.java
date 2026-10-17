package io.github.sinaraz.internal.ogg;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
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

    private final Path localPath;
    private final OggFileIndex localIndex;
    private final String source;
    private final boolean local;
    private final long remoteByteLength;
    private final LongFunction<InputStream> remoteRangeOpener;

    private AudioInfo info;
    private OggPacketReader reader;
    private OggStreamDecoder localSession;
    private OggStreamDecoder remoteSession;
    private final Map<Long, OggStreamDecoder> remoteCandidates = new HashMap<>();
    private int localSegmentIndex;
    private long localSessionId = -1L;
    private long remoteSessionId = -1L;
    private long remoteLastEosOffset = -1L;
    private int remoteSerialNumber;
    private boolean remoteHasSerial;
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

    private record RemoteHeaderBootstrap(OggStreamDecoder session, long firstAudioOffset) {}

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
            this.info = new AudioInfo(Duration.ZERO, first.sampleRate(), first.channels(), 16,
                    first.channels() * 2, 0L, "PCM_SIGNED", ByteOrder.LITTLE_ENDIAN, this.remoteRangeOpener != null,
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
     * @deprecated remote replay from byte zero is no longer used for seeking
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
     * random seeking.
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
        if (localIndexOrRemoteFrameLimitReached()) {
            return -1;
        }
        if (localIndex != null && localIndex.exactDuration()) {
            long remaining = localIndex.frameCount() - framePosition;
            long available = remaining > Long.MAX_VALUE / frameSize
                    ? Long.MAX_VALUE
                    : remaining * frameSize;
            want = (int) Math.min(want, available);
        }

        int written = 0;
        while (written < want) {
            if (pendingPosition >= pendingPcm.length) {
                pendingPcm = nextPcmPage();
                pendingPosition = 0;
                if (remoteRangeActive && pendingPcm != null && pendingPcm.length > 0
                        && pendingPcmStartFrame >= 0) {
                    framePosition = pendingPcmStartFrame;
                    pendingPcmStartFrame = -1L;
                }
                if (pendingPcm == null || pendingPcm.length == 0) {
                    pendingPcm = new byte[0];
                    break;
                }
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
        if (!local && remoteRangeOpener == null) {
            throw new SeekNotSupportedException("seek",
                    "this remote Ogg source does not provide HTTP byte-range access", null);
        }
        long target = framesAt(position, info.sampleRate());
        if (local && localIndex.exactDuration()) {
            target = Math.min(target, localIndex.frameCount());
        }
        if (local) {
            resetLocalReader();
        } else {
            target = resetRemoteReader(target);
        }
        if (target == 0) {
            return;
        }
        if (local && localIndex.exactDuration() && target == localIndex.frameCount()) {
            framePosition = target;
            eof = true;
            return;
        }

        int frameSize = info.frameSize();
        int scratchBytes = Math.max(frameSize, 8192 - (8192 % frameSize));
        byte[] scratch = new byte[scratchBytes];
        while (framePosition < target) {
            long remaining = target - framePosition;
            long remainingBytes = remaining > Long.MAX_VALUE / frameSize
                    ? Long.MAX_VALUE
                    : remaining * frameSize;
            int request = (int) Math.min(scratch.length, remainingBytes);
            int count = read(scratch, 0, request);
            if (count < 0) {
                if (local && localIndex.exactDuration() && framePosition < target) {
                    throw new CorruptFileException("seek", "Ogg stream ended before the indexed seek target",
                            localPath, null);
                }
                break;
            }
        }
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
            if (page.streamId() != segment.streamId()) {
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
            if (page.endOfStream()) {
                if (segment.exactDuration() && localSession.outputFrames() != segment.frameCount()) {
                    throw new CorruptFileException("read",
                            "decoded Ogg sample count " + localSession.outputFrames()
                                    + " differs from indexed count " + segment.frameCount(),
                            localPath, null);
                }
                localSession.close();
                localSession = null;
                localSessionId = -1L;
                localSegmentIndex++;
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
                updateRemoteGranuleOffset(remoteSession);
                if (remoteRangeActive && pcm.length > 0) {
                    pendingPcmStartFrame = pageStartFrame(page, pcm, remoteSession.info().channels());
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
                remoteHasSerial = true;
                remoteCandidates.remove(page.streamId());
                clearRemoteCandidates();
                if (candidate.headersReady()) {
                    acceptRemoteFormat(candidate.info());
                }
                updateRemoteGranuleOffset(candidate);
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

    private void updateRemoteGranuleOffset(OggStreamDecoder session) {
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
    }

    private long pageStartFrame(OggPage page, byte[] pcm, int channels) {
        if (page.granulePosition() < 0 || !remoteGranuleOffsetReady) {
            return -1L;
        }
        long endFrame = page.granulePosition() - remoteGranuleOffsetFrames;
        long pageFrames = pcm.length / (channels * 2L);
        return Math.max(0L, endFrame - pageFrames);
    }

    private void resetLocalReader() throws IOException {
        InputStream replacement = Files.newInputStream(localPath);
        OggPacketReader newReader = new OggPacketReader(replacement, source);
        closeSessions();
        OggPacketReader old = reader;
        reader = newReader;
        if (old != null) {
            old.close();
        }
        localSegmentIndex = 0;
        localSessionId = -1L;
        pendingPcm = new byte[0];
        pendingPosition = 0;
        framePosition = 0;
        eof = false;
    }

    private long resetRemoteReader(long targetFrame) throws IOException {
        if (remoteRangeOpener == null) {
            throw new SeekNotSupportedException("seek",
                    "this remote Ogg source does not provide HTTP byte-range access", null);
        }
        int selectedSerial = remoteSerialNumber;
        boolean hadSelectedSerial = remoteHasSerial;
        long granuleOffset = remoteGranuleOffsetFrames;
        boolean hadGranuleOffset = remoteGranuleOffsetReady;

        OggPacketReader oldReader = reader;
        closeSessions();
        reader = null;
        pendingPcm = new byte[0];
        pendingPosition = 0;
        pendingPcmStartFrame = -1L;
        framePosition = 0L;
        eof = false;
        remoteRangeActive = false;
        remoteSelectedEnded = false;
        remoteLastEosOffset = -1L;
        remoteSessionId = -1L;
        remoteSampleRate = 0;
        remoteChannels = 0;
        remoteSawSupported = false;
        remoteSerialNumber = 0;
        remoteHasSerial = false;
        remoteGranuleOffsetFrames = 0L;
        remoteGranuleOffsetReady = false;

        try {
            if (oldReader != null) {
                oldReader.close();
            }
            if (targetFrame <= 0) {
                resetRemoteFromStart();
                return 0L;
            }
            if (!hadSelectedSerial || !hadGranuleOffset) {
                throw new SeekNotSupportedException("seek",
                        "remote Ogg stream has no established granule origin for range seeking", null);
            }

            RemoteHeaderBootstrap bootstrap = bootstrapRemoteHeaders(selectedSerial);
            OggStreamDecoder freshSession = bootstrap.session();
            if (freshSession.info().sampleRate() != info.sampleRate()
                    || freshSession.info().channels() != info.channels()) {
                freshSession.close();
                throw new UnsupportedAudioFormatException("seek",
                        "remote Ogg format changed while preparing a range seek", null, null);
            }

            int prerollFrames = freshSession.info().codec() == OggHeaderParser.Codec.OPUS
                    ? 3_840 : 16_384;
            long targetGranule = saturatingAdd(targetFrame, granuleOffset);
            long granuleTarget = Math.max(0L, targetGranule - prerollFrames);
            OggPageProbe.PageHeader anchor = OggPageProbe.findPageAtOrBefore(
                    remoteRangeOpener, remoteByteLength, selectedSerial, granuleTarget);
            if (anchor == null && bootstrap.firstAudioOffset() > 0) {
                anchor = OggPageProbe.findFirstAudioPage(remoteRangeOpener, remoteByteLength, selectedSerial);
            }
            if (anchor == null || anchor.byteOffset() <= 0
                    || (bootstrap.firstAudioOffset() >= 0
                    && anchor.byteOffset() < bootstrap.firstAudioOffset())) {
                freshSession.close();
                throw new SeekNotSupportedException("seek",
                        "remote Ogg has no standalone nonzero audio-page boundary for this range seek", null);
            }

            boolean firstAudioPage = anchor.byteOffset() == bootstrap.firstAudioOffset();
            freshSession.prepareForRangeStart(firstAudioPage);
            InputStream rangedInput;
            try {
                rangedInput = remoteRangeOpener.apply(anchor.byteOffset());
            } catch (RuntimeException failure) {
                freshSession.close();
                throw failure;
            }
            if (rangedInput == null) {
                freshSession.close();
                throw new IOException("remote Ogg range opener returned no input stream");
            }
            OggPacketReader newReader;
            try {
                newReader = new OggPacketReader(rangedInput, source, anchor.byteOffset(), selectedSerial);
            } catch (RuntimeException failure) {
                freshSession.close();
                try {
                    rangedInput.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                throw failure;
            }

            reader = newReader;
            remoteSession = freshSession;
            remoteSessionId = 0L;
            remoteSerialNumber = selectedSerial;
            remoteHasSerial = true;
            remoteGranuleOffsetFrames = granuleOffset;
            remoteGranuleOffsetReady = true;
            remoteSampleRate = info.sampleRate();
            remoteChannels = info.channels();
            remoteSawSupported = true;
            remoteRangeActive = true;
            return targetFrame;
        } catch (IOException | RuntimeException failure) {
            closeSessions();
            if (reader != null) {
                try {
                    reader.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
                reader = null;
            }
            eof = true;
            closed = true;
            throw failure;
        }
    }

    private RemoteHeaderBootstrap bootstrapRemoteHeaders(int serial) throws IOException {
        InputStream input = remoteRangeOpener.apply(0L);
        if (input == null) {
            throw new IOException("remote Ogg range opener returned no input stream");
        }
        OggPacketReader probe;
        try {
            probe = new OggPacketReader(input, source);
        } catch (RuntimeException failure) {
            try {
                input.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }

        OggStreamDecoder session = null;
        try {
            while (true) {
                OggPage page = probe.readPage();
                if (page == null) {
                    throw new CorruptFileException("seek", "remote Ogg ended before the first seekable audio page",
                            null, null);
                }
                if (page.serialNumber() != serial) {
                    continue;
                }
                if (page.beginningOfStream()) {
                    if (session != null) {
                        session.close();
                    }
                    session = new OggStreamDecoder(source);
                }
                if (session == null) {
                    continue;
                }
                boolean audioPacket = session.processHeaderPage(page);
                if (session.headersReady() && audioPacket && !page.continued()
                        && page.granulePosition() > 0) {
                    RemoteHeaderBootstrap result = new RemoteHeaderBootstrap(session, page.byteOffset());
                    probe.close();
                    session = null;
                    return result;
                }
            }
        } finally {
            if (session != null) {
                session.close();
            }
            probe.close();
        }
    }

    private void resetRemoteFromStart() throws IOException {
        InputStream input = remoteRangeOpener.apply(0L);
        if (input == null) {
            throw new IOException("remote Ogg range opener returned no input stream");
        }
        try {
            reader = new OggPacketReader(input, source);
        } catch (RuntimeException failure) {
            try {
                input.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
        remoteSessionId = -1L;
        remoteLastEosOffset = -1L;
        remoteSampleRate = 0;
        remoteChannels = 0;
        remoteSelectedEnded = false;
        remoteSawSupported = false;
        remoteSerialNumber = 0;
        remoteHasSerial = false;
        remoteGranuleOffsetFrames = 0L;
        remoteGranuleOffsetReady = false;
        remoteRangeActive = false;
        initializeRemote();
        if (remoteSampleRate != info.sampleRate() || remoteChannels != info.channels()) {
            throw new UnsupportedAudioFormatException("seek",
                    "remote Ogg format changed while reopening the stream", null, null);
        }
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

    private boolean localIndexOrRemoteFrameLimitReached() {
        return local && localIndex.exactDuration() && framePosition >= localIndex.frameCount();
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
