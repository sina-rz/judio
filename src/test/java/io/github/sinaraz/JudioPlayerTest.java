package io.github.sinaraz;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sinaraz.audio.AudioOutput;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.audio.SimulatedAudioOutput;
import io.github.sinaraz.error.PlaybackStateException;
import io.github.sinaraz.error.QueueException;
import io.github.sinaraz.model.ErrorKind;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;
import io.github.sinaraz.model.TrackMetadataStatus;
import io.github.sinaraz.support.Await;
import io.github.sinaraz.support.TestAudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end tests: real WAV files, real decoder, paced fake device, and the facade's queue logic. */
class JudioPlayerTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    @TempDir
    Path dir;

    private JudioPlayer player;
    private final AtomicInteger completions = new AtomicInteger();
    private final List<PlaybackError> errors = new CopyOnWriteArrayList<>();

    private JudioPlayer newPlayer(PlaybackConfig config) {
        player = JudioPlayer.builder().config(config).simulatedOutput().random(new Random(5)).build();
        player.addListener(new PlaybackListener() {
            @Override
            public void onPlaybackCompleted(Track track) {
                completions.incrementAndGet();
            }

            @Override
            public void onError(PlaybackError error) {
                errors.add(error);
            }
        });
        return player;
    }

    @BeforeEach
    void setUp() {
        completions.set(0);
    }

    @AfterEach
    void tearDown() {
        if (player != null) {
            player.close();
            player.close(); // idempotent
        }
    }

    private Path wav(String name, double seconds) throws Exception {
        int frames = (int) Math.round(seconds * 8000);
        return TestAudio.writeWav(dir.resolve(name), frames, 1, 8000, 16);
    }

    private PlaybackSnapshot snap() {
        return player.snapshot();
    }

    @Test
    void queueAdvancesThroughAllTracksAndEndsOnTheLast() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        player.addFiles(List.of(wav("a.wav", 0.2), wav("b.wav", 0.2), wav("c.wav", 0.2)));
        player.play();
        Await.until("queue finished", WAIT, () -> snap().state() == PlaybackState.ENDED);
        // Completion events are delivered asynchronously, so wait for all three before asserting.
        Await.until("three completions", WAIT, () -> completions.get() == 3);
        assertEquals(3, completions.get());
        assertEquals(2, snap().queueIndex(), "repeat OFF stops on the last track");
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void repeatOneReplaysTheSameTrack() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        player.addFiles(List.of(wav("solo.wav", 0.15)));
        player.setRepeat(RepeatMode.ONE);
        player.play();
        Await.until("two completions", WAIT, () -> completions.get() >= 2);
        assertEquals(0, snap().queueIndex());
    }

    @Test
    void repeatAllWrapsToTheFirstTrack() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        player.addFiles(List.of(wav("p.wav", 0.15), wav("q.wav", 0.15)));
        player.setRepeat(RepeatMode.ALL);
        player.play();
        Await.until("three completions", WAIT, () -> completions.get() >= 3);
        assertTrue(snap().queueIndex() == 0 || snap().queueIndex() == 1);
        assertEquals(RepeatMode.ALL, snap().repeat());
    }

    @Test
    void unplayableFileIsReportedAndPlaybackMovesOn() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        Path junk = Files.write(dir.resolve("junk.wav"),
                "this is not audio at all".getBytes(StandardCharsets.US_ASCII));
        player.addFiles(List.of(junk, wav("good.wav", 0.2)));
        player.play();
        Await.until("error reported", WAIT, () -> !errors.isEmpty());
        assertEquals(ErrorKind.UNSUPPORTED_FORMAT, errors.get(0).kind());
        Await.until("good track completes", WAIT, () -> completions.get() == 1);
        assertEquals(1, snap().queueIndex());
    }

    @Test
    void previousRestartsPastThresholdAndGoesBackBelowIt() throws Exception {
        PlaybackConfig config = PlaybackConfig.builder()
                .previousRestartThreshold(Duration.ofMillis(300))
                .build();
        newPlayer(config);
        player.addFiles(List.of(wav("first.wav", 2.0), wav("second.wav", 2.0)));
        player.playAt(1);
        Await.until("second track 0.5 s in", WAIT,
                () -> snap().position().compareTo(Duration.ofMillis(500)) > 0);
        assertEquals(1, snap().queueIndex());

        player.previous();
        Await.until("restarted", WAIT, () -> snap().position().compareTo(Duration.ofMillis(300)) < 0);
        assertEquals(1, snap().queueIndex(), "past the threshold, previous restarts the current track");

        player.previous();
        Await.until("moved back", WAIT, () -> snap().queueIndex() == 0);
    }

    @Test
    void metadataIsLoadedAsynchronouslyWithoutPublishingFullQueueSnapshotsPerTrack() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        AtomicInteger queueNotifications = new AtomicInteger();
        AtomicInteger metadataNotifications = new AtomicInteger();
        player.addListener(new PlaybackListener() {
            @Override
            public void onQueueChanged(List<Track> queue, io.github.sinaraz.model.QueueSnapshot state) {
                queueNotifications.incrementAndGet();
            }

            @Override
            public void onTrackMetadataChanged(Track track) {
                metadataNotifications.incrementAndGet();
            }
        });
        player.addFiles(List.of(wav("m1.wav", 0.1), wav("m2.wav", 0.1)));
        Await.until("metadata resolved", WAIT,
                () -> player.queue().stream().allMatch(t -> t.metadataStatus() != TrackMetadataStatus.PENDING));
        Await.until("metadata notifications delivered", WAIT, () -> metadataNotifications.get() == 2);
        assertEquals(1, queueNotifications.get(), "only the structural queue change needs a full snapshot");
    }

    @Test
    void mutationsAfterCloseThrowPlaybackStateException() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        Path file = wav("closed.wav", 0.2);
        player.addFiles(List.of(file));
        player.close();

        org.junit.jupiter.api.Assertions.assertThrows(PlaybackStateException.class,
                () -> player.addFiles(List.of(file)));
        org.junit.jupiter.api.Assertions.assertThrows(PlaybackStateException.class,
                () -> player.addUrl(java.net.URI.create("https://example.com/x.mp3")));
        org.junit.jupiter.api.Assertions.assertThrows(PlaybackStateException.class,
                () -> player.setRepeat(RepeatMode.ALL));
        org.junit.jupiter.api.Assertions.assertThrows(PlaybackStateException.class,
                () -> player.setShuffle(true));
        org.junit.jupiter.api.Assertions.assertThrows(PlaybackStateException.class,
                () -> player.remove(0));
        org.junit.jupiter.api.Assertions.assertThrows(PlaybackStateException.class,
                player::clear);
        // Reading state remains legal after close.
        assertEquals(PlaybackState.CLOSED, snap().state());
        assertEquals(1, player.queue().size());
    }

    @Test
    void addFilesRejectsNullAndEmptyWithoutSideEffects() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        Path file = wav("ok.wav", 0.1);
        org.junit.jupiter.api.Assertions.assertThrows(QueueException.class,
                () -> player.addFiles(java.util.Arrays.asList(file, null)));
        assertEquals(0, player.queue().size());
        assertTrue(player.addFiles(List.of()).isEmpty());
        assertEquals(0, player.queue().size());
    }

    @Test
    void insertFilePlacesEntryAtIndex() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        player.addFiles(List.of(wav("a.wav", 0.1), wav("c.wav", 0.1)));
        player.insertFile(1, wav("b.wav", 0.1));
        assertEquals(3, player.queue().size());
        assertEquals("b", player.queue().get(1).title());
    }

    @Test
    void closeUsesOneDeadlineAcrossBlockedAudioAndListenerThreads() throws Exception {
        PlaybackConfig config = PlaybackConfig.builder().closeTimeout(Duration.ofMillis(400)).build();
        BlockingAudioOutput output = new BlockingAudioOutput();
        CountDownLatch listenerEntered = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        player = JudioPlayer.builder().config(config).output(() -> output).build();
        player.addListener(new PlaybackListener() {
            @Override
            public void onQueueChanged(List<Track> queue, io.github.sinaraz.model.QueueSnapshot state) {
                listenerEntered.countDown();
                awaitUninterruptibly(releaseListener);
            }
        });
        player.addFiles(List.of(wav("blocked.wav", 5.0)));
        assertTrue(listenerEntered.await(2, TimeUnit.SECONDS), "listener should be blocked before closing");
        player.play();
        assertTrue(output.writeEntered.await(5, TimeUnit.SECONDS), "audio worker should be blocked in output.write");

        long started = System.nanoTime();
        try {
            player.close();
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMillis < 650,
                    "sequential shutdown phases must share the 400 ms deadline; elapsed=" + elapsedMillis + " ms");
        } finally {
            releaseListener.countDown();
            output.releaseWrite.countDown();
        }
        assertTrue(output.closed.await(2, TimeUnit.SECONDS), "blocked audio worker should release its output");
    }

    @Test
    void closeIsSafeWhilePlaying() throws Exception {
        newPlayer(PlaybackConfig.defaults());
        player.addFiles(List.of(wav("long.wav", 5.0)));
        player.play();
        Await.until("playing", WAIT, () -> snap().state() == PlaybackState.PLAYING);
        player.close();
        player.close();
        assertEquals(PlaybackState.CLOSED, snap().state());
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class BlockingAudioOutput implements AudioOutput {
        private final CountDownLatch writeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void open(AudioFormat format) {}

        @Override
        public void write(byte[] data, int offset, int length) {
            writeEntered.countDown();
            awaitUninterruptibly(releaseWrite);
        }

        @Override
        public void start() {}

        @Override
        public void stop() {}

        @Override
        public void flush() {}

        @Override
        public void drain() {}

        @Override
        public long bufferedFrames() {
            return 0;
        }

        @Override
        public boolean applyVolume(double linearVolume, boolean muted) {
            return true;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
