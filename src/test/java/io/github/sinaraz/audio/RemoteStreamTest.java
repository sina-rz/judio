package io.github.sinaraz.audio;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.error.RemoteSourceException;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.Track;
import io.github.sinaraz.support.Await;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streams real files from a public host, so the tests exercise the same path a user takes. They
 * are skipped when the host cannot be reached.
 */
class RemoteStreamTest {

    static final URI MP3 = URI.create(
            "https://file-examples.com/storage/fe9ac44c8a6acc7d7989e28/2017/11/file_example_MP3_5MG.mp3");
    static final URI WAV = URI.create(
            "https://file-examples.com/storage/fe9ac44c8a6acc7d7989e28/2017/11/file_example_WAV_1MG.wav");
    static final URI MISSING = URI.create(
            "https://file-examples.com/storage/fe9ac44c8a6acc7d7989e28/2017/11/no_such_file_judio.mp3");

    private static final int SAMPLE_RATE = 44_100;
    private static final int FRAME_BYTES = 4;   // 16-bit stereo

    private static DecoderFactory factory;

    @BeforeAll
    static void requireNetwork() {
        StreamOptions options = StreamOptions.builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .build();
        factory = DecoderFactory.standard(options);
        boolean reachable;
        try {
            HttpResponse<Void> head = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(HttpRequest.newBuilder(WAV).method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(15)).build(),
                            HttpResponse.BodyHandlers.discarding());
            reachable = head.statusCode() == 200;
        } catch (IOException e) {
            reachable = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reachable = false;
        }
        Assumptions.assumeTrue(reachable, "file-examples.com is not reachable; skipping network stream tests");
    }

    @Test
    void streamsWavAndDecodesCanonicalPcm() throws IOException {
        long bytes = 0;
        try (AudioDecoder decoder = factory.open(MediaLocation.of(WAV))) {
            assertEquals(SAMPLE_RATE, decoder.audioInfo().sampleRate());
            assertEquals(2, decoder.audioInfo().channels());
            assertEquals(16, decoder.audioInfo().sampleSizeBits());
            assertTrue(decoder.isSeekable(), "a range-capable WAV stream should be seekable");
            assertTrue(decoder.audioInfo().duration().compareTo(Duration.ofSeconds(5)) > 0);

            byte[] buffer = new byte[8192];
            int n;
            while ((n = decoder.read(buffer, 0, buffer.length)) >= 0) {
                bytes += n;
            }
        }
        // The file is about 5.9 seconds of 16-bit stereo at 44.1 kHz, so about 1 MB of PCM.
        assertTrue(bytes > 1_000_000, "decoded only " + bytes + " bytes");
    }

    @Test
    void seeksWithinWavStream() throws IOException {
        try (AudioDecoder decoder = factory.open(MediaLocation.of(WAV))) {
            decoder.seek(Duration.ofSeconds(3));
            assertEquals(3L * SAMPLE_RATE, decoder.framePosition());

            byte[] buffer = new byte[SAMPLE_RATE * FRAME_BYTES];
            int n = decoder.read(buffer, 0, buffer.length);
            assertEquals(buffer.length, n);
            assertEquals(4L * SAMPLE_RATE, decoder.framePosition());

            decoder.seek(Duration.ofSeconds(1000));
            assertEquals(-1, decoder.read(buffer, 0, buffer.length), "past the end should read end of stream");
        }
    }

    @Test
    void streamsMp3WithDurationAndSeeks() throws IOException {
        try (AudioDecoder decoder = factory.open(MediaLocation.of(MP3))) {
            assertEquals(SAMPLE_RATE, decoder.audioInfo().sampleRate());
            assertEquals(2, decoder.audioInfo().channels());
            assertTrue(decoder.isSeekable(), "a range-capable MP3 stream should be seekable");
            // The Xing header in this file gives the length: about 2 minutes 13 seconds.
            long seconds = decoder.audioInfo().duration().toSeconds();
            assertTrue(seconds >= 130 && seconds <= 135, "duration was " + decoder.audioInfo().duration());

            decoder.seek(Duration.ofSeconds(60));
            byte[] buffer = new byte[SAMPLE_RATE * FRAME_BYTES];
            assertEquals(buffer.length, decoder.read(buffer, 0, buffer.length));
            long position = decoder.framePosition();
            assertTrue(Math.abs(position - 61L * SAMPLE_RATE) < SAMPLE_RATE / 100,
                    "after seeking to 60 s and reading 1 s, position was " + position);
            assertTrue(nonSilent(buffer), "audio after a seek should not be silence");

            decoder.seek(Duration.ZERO);
            assertEquals(0L, decoder.framePosition());
            decoder.read(buffer, 0, buffer.length);
            assertTrue(nonSilent(buffer));
        }
    }

    @Test
    void playerSeeksWithinOnlineWavAndFinishes() throws Exception {
        seekThroughOnlinePlayer(WAV, Duration.ofSeconds(4));
    }

    @Test
    void playerSeeksWithinOnlineMp3AndFinishes() throws Exception {
        seekThroughOnlinePlayer(MP3, Duration.ofSeconds(120));
    }

    /**
     * Loads a hosted file through the full player, pauses, seeks while paused, resumes, and waits for
     * the end. Seeking while paused keeps the position check exact.
     */
    private static void seekThroughOnlinePlayer(URI uri, Duration target) throws Exception {
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try (JudioPlayer player = JudioPlayer.builder().simulatedOutput().build()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onPlaybackCompleted(Track track) {
                    finished.complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    finished.completeExceptionally(new IllegalStateException(error.message()));
                }
            });
            player.addUrl(uri);
            player.play();
            Await.until("online track playing", Duration.ofSeconds(60),
                    () -> player.snapshot().state() == PlaybackState.PLAYING);

            player.pause();
            player.seek(target);
            Await.until("seek on the online stream lands", Duration.ofSeconds(60),
                    () -> player.snapshot().state() == PlaybackState.PAUSED
                            && player.snapshot().position().compareTo(target) >= 0);
            Duration landed = player.snapshot().position();
            assertTrue(landed.minus(target).compareTo(Duration.ofSeconds(1)) < 0,
                    "seek to " + target + " landed at " + landed);

            player.resume();
            finished.get(90, TimeUnit.SECONDS);
            assertEquals(PlaybackState.ENDED, player.snapshot().state());
        }
    }

    @Test
    void reportsHttpErrorStatusFromTheServer() {
        RemoteSourceException error = assertThrows(RemoteSourceException.class,
                () -> factory.open(MediaLocation.of(MISSING)));
        assertEquals(404, error.statusCode());
        assertEquals(MISSING, error.uri());
    }

    private static boolean nonSilent(byte[] pcm) {
        for (byte b : pcm) {
            if (b != 0) {
                return true;
            }
        }
        return false;
    }
}
