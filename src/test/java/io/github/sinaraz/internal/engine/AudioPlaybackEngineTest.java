package io.github.sinaraz.internal.engine;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.AudioOutput;
import io.github.sinaraz.audio.SimulatedAudioOutput;
import io.github.sinaraz.error.PlaybackStateException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.ErrorKind;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.Track;
import io.github.sinaraz.support.Await;
import io.github.sinaraz.support.RecordingOutput;
import io.github.sinaraz.support.SyntheticDecoder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Playback integration tests driven through the real engine with synthetic decoders and fake output. */
class AudioPlaybackEngineTest {

    private static final int RATE = 8000;
    private static final Duration WAIT = Duration.ofSeconds(10);

    private PlaybackEventBus bus;
    private AudioPlaybackEngine engine;
    private final List<PlaybackError> errors = new CopyOnWriteArrayList<>();
    private final List<PlaybackState> states = new CopyOnWriteArrayList<>();
    private final AtomicInteger completions = new AtomicInteger();
    private final Track track = Track.fromPath(Path.of("synthetic.wav"));

    private AudioPlaybackEngine newEngine(Supplier<AudioDecoder> decoders, Supplier<AudioOutput> outputs,
                                          PlaybackListener... extra) {
        bus = new PlaybackEventBus(Duration.ofSeconds(2));
        bus.addListener(new PlaybackListener() {
            @Override
            public void onStateChanged(PlaybackSnapshot s) {
                states.add(s.state());
            }

            @Override
            public void onPlaybackCompleted(Track t) {
                completions.incrementAndGet();
            }

            @Override
            public void onError(PlaybackError error) {
                errors.add(error);
            }
        });
        for (PlaybackListener l : extra) {
            bus.addListener(l);
        }
        engine = new AudioPlaybackEngine(PlaybackConfig.defaults(), path -> decoders.get(), outputs, bus,
                () -> QueueSnapshot.EMPTY);
        return engine;
    }

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
        if (bus != null) {
            bus.close();
        }
    }

    private void loadAndWaitReady() throws InterruptedException {
        engine.load(track);
        Await.until("READY", WAIT, () -> engine.snapshot().state() == PlaybackState.READY
                || engine.snapshot().state() == PlaybackState.ERROR);
    }

    private void awaitState(PlaybackState wanted) throws InterruptedException {
        Await.until("state " + wanted, WAIT, () -> engine.snapshot().state() == wanted);
    }

    private static Supplier<AudioDecoder> synthetic(double seconds) {
        return () -> new SyntheticDecoder(RATE, 2, seconds);
    }

    @Test
    void playsToEndWithExactPcmAndReportsCompletion() throws Exception {
        RecordingOutput out = new RecordingOutput();
        newEngine(synthetic(0.5), () -> out);
        loadAndWaitReady();
        engine.play();
        awaitState(PlaybackState.ENDED);

        byte[] pcm = out.recorded();
        assertEquals(4000 * 4, pcm.length, "every decoded frame reaches the output, including the last");
        for (int i = 0; i < pcm.length; i += 2) {
            short s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
            assertTrue(Math.abs(s - SyntheticDecoder.LEVEL) <= 1, "sample " + i + " was " + s);
        }
        assertEquals(1, completions.get());
        assertEquals(Duration.ofMillis(500), engine.snapshot().position());
        assertTrue(errors.isEmpty());
        assertEquals(2, out.format().getChannels());
        assertEquals(RATE, (int) out.format().getSampleRate());
    }

    @Test
    void volumeIsAppliedInSoftwareWhenTheDeviceCannotDoIt() throws Exception {
        RecordingOutput out = new RecordingOutput(false);
        newEngine(synthetic(0.25), () -> out);
        loadAndWaitReady();
        engine.setVolume(0.5);
        engine.play();
        awaitState(PlaybackState.ENDED);

        byte[] pcm = out.recorded();
        short s = (short) ((pcm[0] & 0xFF) | (pcm[1] << 8));
        assertTrue(Math.abs(s - SyntheticDecoder.LEVEL / 2) <= 2, "half volume, got " + s);
        assertFalse(out.volumeCalls().isEmpty(), "engine asks the device first");
    }

    @Test
    void mutedPlaybackProducesSilenceAndKeepsPositionAdvancing() throws Exception {
        RecordingOutput out = new RecordingOutput(false);
        newEngine(synthetic(0.25), () -> out);
        loadAndWaitReady();
        engine.setMuted(true);
        engine.play();
        awaitState(PlaybackState.ENDED);
        byte[] pcm = out.recorded();
        assertEquals(2000 * 4, pcm.length);
        for (byte b : pcm) {
            assertEquals(0, b);
        }
        assertTrue(engine.isMuted());
        assertEquals(Duration.ofMillis(250), engine.snapshot().position());
    }

    @Test
    void hardwareVolumeLeavesSamplesAloneWhenDeviceReportsSupport() throws Exception {
        RecordingOutput out = new RecordingOutput(true);
        newEngine(synthetic(0.25), () -> out);
        loadAndWaitReady();
        engine.setVolume(0.5);
        engine.play();
        awaitState(PlaybackState.ENDED);
        byte[] pcm = out.recorded();
        short s = (short) ((pcm[0] & 0xFF) | (pcm[1] << 8));
        assertTrue(Math.abs(s - SyntheticDecoder.LEVEL) <= 1, "device owns the gain, got " + s);
        assertTrue(out.volumeCalls().stream().anyMatch(c -> c.equals("0.5,false")), out.volumeCalls().toString());
    }

    @Test
    void doubleSpeedHalvesOutputLength() throws Exception {
        RecordingOutput out = new RecordingOutput();
        newEngine(synthetic(1.0), () -> out);
        loadAndWaitReady();
        engine.setPlaybackSpeed(2.0);
        engine.play();
        awaitState(PlaybackState.ENDED);
        int frames = out.recorded().length / 4;
        assertTrue(Math.abs(frames - RATE / 2) <= 4, "about half the frames at 2x, got " + frames);
        assertEquals(2.0, engine.playbackSpeed(), 1e-9);
    }

    @Test
    void pauseFreezesPositionAndResumeContinues() throws Exception {
        newEngine(synthetic(3.0), () -> new SimulatedAudioOutput(65536));
        loadAndWaitReady();
        engine.play();
        Await.until("progress past 0.5 s", WAIT,
                () -> engine.snapshot().position().compareTo(Duration.ofMillis(500)) > 0);

        engine.pause();
        awaitState(PlaybackState.PAUSED);
        Duration frozen = engine.snapshot().position();
        Thread.sleep(300);
        assertEquals(frozen, engine.snapshot().position(), "position must not move while paused");
        assertEquals(PlaybackState.PAUSED, engine.snapshot().state());

        engine.resume();
        awaitState(PlaybackState.PLAYING);
        Await.until("position advances after resume", WAIT,
                () -> engine.snapshot().position().compareTo(frozen.plusMillis(200)) > 0);
    }

    @Test
    void seekWhilePausedMovesPositionAndStaysPaused() throws Exception {
        newEngine(synthetic(3.0), () -> new SimulatedAudioOutput(65536));
        loadAndWaitReady();
        engine.play();
        awaitState(PlaybackState.PLAYING);
        engine.pause();
        awaitState(PlaybackState.PAUSED);

        engine.seek(Duration.ofSeconds(1));
        Await.until("position at 1 s", WAIT,
                () -> engine.snapshot().position().equals(Duration.ofSeconds(1))
                        && engine.snapshot().state() == PlaybackState.PAUSED);
    }

    @Test
    void seekWhilePlayingResumesPlaying() throws Exception {
        newEngine(synthetic(3.0), () -> new SimulatedAudioOutput(65536));
        loadAndWaitReady();
        engine.play();
        awaitState(PlaybackState.PLAYING);
        engine.seek(Duration.ofMillis(2000));
        Await.until("seek lands and playback continues", WAIT,
                () -> engine.snapshot().state() == PlaybackState.PLAYING
                        && engine.snapshot().position().compareTo(Duration.ofMillis(2000)) >= 0);
    }

    @Test
    void absoluteSeekClampsToDurationAndRelativeSeekClampsAtZero() throws Exception {
        newEngine(synthetic(2.0), () -> new SimulatedAudioOutput(65536));
        loadAndWaitReady();
        engine.play();
        awaitState(PlaybackState.PLAYING);
        engine.pause();
        awaitState(PlaybackState.PAUSED);

        engine.seek(Duration.ofHours(1));
        Await.until("clamped to end", WAIT, () -> engine.snapshot().position().equals(Duration.ofSeconds(2))
                && engine.snapshot().state() == PlaybackState.PAUSED);

        engine.seekRelative(Duration.ofSeconds(-60));
        Await.until("clamped to zero", WAIT, () -> engine.snapshot().position().isZero());
    }

    @Test
    void nonSeekableSourceRejectsSeekImmediately() throws Exception {
        newEngine(() -> new SyntheticDecoder(RATE, 1, 1.0, false, -1), () -> new RecordingOutput());
        loadAndWaitReady();
        assertThrows(SeekNotSupportedException.class, () -> engine.seek(Duration.ofMillis(100)));
    }

    @Test
    void invalidCommandsFailWithStateExceptions() throws Exception {
        newEngine(synthetic(1.0), () -> new RecordingOutput());
        assertThrows(PlaybackStateException.class, () -> engine.play());
        assertThrows(PlaybackStateException.class, () -> engine.pause());
        assertThrows(PlaybackStateException.class, () -> engine.seek(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> engine.setVolume(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> engine.setPlaybackSpeed(Double.POSITIVE_INFINITY));
    }

    @Test
    void speedIsClampedToTheConfiguredRange() {
        newEngine(synthetic(1.0), () -> new RecordingOutput());
        engine.setPlaybackSpeed(0.0);
        assertEquals(0.5, engine.playbackSpeed(), 1e-9, "below minimum clamps to 0.5x");
        engine.setPlaybackSpeed(10.0);
        assertEquals(2.0, engine.playbackSpeed(), 1e-9, "above maximum clamps to 2.0x");
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherCommands() throws Exception {
        newEngine(synthetic(1.0), () -> new RecordingOutput());
        loadAndWaitReady();
        engine.close();
        engine.close();
        assertEquals(PlaybackState.CLOSED, engine.snapshot().state());
        assertThrows(PlaybackStateException.class, () -> engine.play());
        assertThrows(PlaybackStateException.class, () -> engine.load(track));
    }

    @Test
    void decoderFailureMidTrackReportsErrorAndEntersErrorState() throws Exception {
        newEngine(() -> new SyntheticDecoder(RATE, 2, 1.0, true, 1000), () -> new RecordingOutput());
        loadAndWaitReady();
        engine.play();
        awaitState(PlaybackState.ERROR);
        Await.until("error delivered", WAIT, () -> !errors.isEmpty());
        assertEquals(ErrorKind.DECODER_FAILURE, errors.get(0).kind());
        assertEquals(track, errors.get(0).track());
    }

    @Test
    void throwingListenerDoesNotStopPlaybackOrOtherListeners() throws Exception {
        CompletableFuture<Void> seen = new CompletableFuture<>();
        PlaybackListener bad = new PlaybackListener() {
            @Override
            public void onStateChanged(PlaybackSnapshot s) {
                throw new IllegalStateException("listener bug");
            }
        };
        PlaybackListener good = new PlaybackListener() {
            @Override
            public void onPlaybackCompleted(Track t) {
                seen.complete(null);
            }
        };
        RecordingOutput out = new RecordingOutput();
        newEngine(synthetic(0.2), () -> out, bad, good);
        loadAndWaitReady();
        engine.play();
        seen.get(10, TimeUnit.SECONDS); // fails with TimeoutException if the healthy listener never hears it
        assertEquals(PlaybackState.ENDED, engine.snapshot().state());
    }

    @Test
    void stalledDecoderDoesNotBlockPauseOrClose() throws Exception {
        RecordingOutput out = new RecordingOutput();
        AtomicReference<StallingDecoder> made = new AtomicReference<>();
        newEngine(() -> {
            StallingDecoder d = new StallingDecoder();
            made.set(d);
            return d;
        }, () -> out);
        try {
            loadAndWaitReady();
            engine.play();
            Await.until("some audio before the stall", WAIT, () -> out.recorded().length > 0);

            long start = System.nanoTime();
            engine.pause();
            Duration pauseTook = Duration.ofNanos(System.nanoTime() - start);
            assertEquals(PlaybackState.PAUSED, engine.snapshot().state());
            assertTrue(pauseTook.compareTo(Duration.ofMillis(500)) < 0,
                    "pause must not wait for the stalled read, took " + pauseTook);

            start = System.nanoTime();
            engine.close();
            Duration closeTook = Duration.ofNanos(System.nanoTime() - start);
            Duration bound = PlaybackConfig.defaults().closeTimeout().plusSeconds(1);
            assertTrue(closeTook.compareTo(bound) < 0,
                    "close must return within its timeout, took " + closeTook);
        } finally {
            // Let the stalled read finish so the decode thread can exit.
            StallingDecoder d = made.get();
            if (d != null) {
                d.release.complete(null);
            }
        }
    }

    /** Plays a short run of audio, then blocks in read() until released, like a stalled network stream. */
    private static final class StallingDecoder implements AudioDecoder {
        final CompletableFuture<Void> release = new CompletableFuture<>();
        private final SyntheticDecoder inner = new SyntheticDecoder(RATE, 2, 60);
        private int reads;

        @Override
        public AudioInfo audioInfo() {
            return inner.audioInfo();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (++reads <= 2) {
                return inner.read(buffer, offset, length);
            }
            try {
                release.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                return -1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("interrupted while stalled");
            } catch (ExecutionException | TimeoutException e) {
                throw new IOException("stalled read gave up", e);
            }
        }

        @Override
        public void seek(Duration position) throws IOException {
            inner.seek(position);
        }

        @Override
        public boolean isSeekable() {
            return inner.isSeekable();
        }

        @Override
        public long framePosition() {
            return inner.framePosition();
        }

        @Override
        public void close() {
            // Deliberately does not release the stall: close() must return without waiting for it.
        }
    }

    @Test
    void streamedBytesMatchAcrossRepeatedRuns() throws Exception {
        RecordingOutput first = new RecordingOutput();
        newEngine(synthetic(0.1), () -> first);
        loadAndWaitReady();
        engine.play();
        awaitState(PlaybackState.ENDED);
        byte[] a = first.recorded();
        engine.close();
        bus.close();

        RecordingOutput second = new RecordingOutput();
        newEngine(synthetic(0.1), () -> second);
        loadAndWaitReady();
        engine.play();
        awaitState(PlaybackState.ENDED);
        assertArrayEquals(a, second.recorded(), "identical input yields identical output");
    }
}
