package io.github.sinaraz.audio;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.error.RemoteSourceException;
import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.Track;
import io.github.sinaraz.support.Await;
import io.github.sinaraz.support.TestAudio;
import io.github.sinaraz.support.TestMp3;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streams files from a loopback HTTP server started by the test itself, so the
 * full network path (range requests, reconnect offsets, HTTP errors) is
 * exercised in every environment with no external host involved.
 */
class RemoteStreamTest {

    private static final int SAMPLE_RATE = 44_100;
    private static final int FRAME_BYTES = 4;   // 16-bit stereo

    private static HttpServer server;
    private static String base;
    private static DecoderFactory factory;

    private static URI wavUri;
    private static URI mp3Uri;
    private static URI missingUri;

    @BeforeAll
    static void startServer() throws Exception {
        Path dir = Files.createTempDirectory("judio-remote-test");
        byte[] wav = Files.readAllBytes(
                TestAudio.writeWav(dir.resolve("tone.wav"), SAMPLE_RATE * 3, 2, SAMPLE_RATE, 16));
        byte[] mp3 = TestMp3.silentMp3(300);  // ~7.8 s of silent MP3

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tone.wav", exchange -> serve(exchange, wav, "audio/wav"));
        server.createContext("/silent.mp3", exchange -> serve(exchange, mp3, "audio/mpeg"));
        server.createContext("/unknown", exchange -> serve(exchange,
                new byte[] {1, 2, 3, 4, 5, 6}, "application/octet-stream"));
        server.createContext("/missing.mp3", exchange -> {
            byte[] body = "not found".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        wavUri = URI.create(base + "/tone.wav");
        mp3Uri = URI.create(base + "/silent.mp3");
        missingUri = URI.create(base + "/missing.mp3");
        factory = DecoderFactory.standard(StreamOptions.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(10))
                .build());
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Serves {@code body} honouring {@code Range: bytes=N-} with a 206 response. */
    private static void serve(HttpExchange exchange, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(200, -1);
            return;
        }
        String range = exchange.getRequestHeaders().getFirst("Range");
        long from = 0;
        if (range != null && range.startsWith("bytes=")) {
            String spec = range.substring("bytes=".length());
            int dash = spec.indexOf('-');
            if (dash > 0) {
                from = Long.parseLong(spec.substring(0, dash));
            }
        }
        if (from > 0) {
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + from + "-" + (body.length - 1) + "/" + body.length);
            exchange.sendResponseHeaders(206, body.length - from);
        } else {
            exchange.sendResponseHeaders(200, body.length);
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body, (int) from, body.length - (int) from);
        }
    }

    @Test
    void streamsWavAndDecodesCanonicalPcm() throws IOException {
        long bytes = 0;
        try (AudioDecoder decoder = factory.open(MediaLocation.of(wavUri))) {
            assertEquals(SAMPLE_RATE, decoder.audioInfo().sampleRate());
            assertEquals(2, decoder.audioInfo().channels());
            assertEquals(16, decoder.audioInfo().sampleSizeBits());
            assertTrue(decoder.isSeekable(), "a range-capable WAV stream should be seekable");
            assertEquals(3, decoder.audioInfo().duration().toSeconds());

            byte[] buffer = new byte[8192];
            int n;
            while ((n = decoder.read(buffer, 0, buffer.length)) >= 0) {
                bytes += n;
            }
        }
        assertEquals(3L * SAMPLE_RATE * FRAME_BYTES, bytes);
    }

    @Test
    void seeksWithinWavStream() throws IOException {
        try (AudioDecoder decoder = factory.open(MediaLocation.of(wavUri))) {
            decoder.seek(Duration.ofSeconds(1));
            assertEquals(1L * SAMPLE_RATE, decoder.framePosition());

            byte[] buffer = new byte[SAMPLE_RATE * FRAME_BYTES];
            int n = decoder.read(buffer, 0, buffer.length);
            assertEquals(buffer.length, n);
            assertEquals(2L * SAMPLE_RATE, decoder.framePosition());

            decoder.seek(Duration.ofSeconds(1000));
            assertEquals(-1, decoder.read(buffer, 0, buffer.length), "past the end should read end of stream");
        }
    }

    @Test
    void streamsMp3WithDurationAndSeeks() throws IOException {
        try (AudioDecoder decoder = factory.open(MediaLocation.of(mp3Uri))) {
            assertEquals(SAMPLE_RATE, decoder.audioInfo().sampleRate());
            assertEquals(2, decoder.audioInfo().channels());
            assertTrue(decoder.isSeekable(), "a range-capable MP3 stream should be seekable");
            // No Xing header in the generated file: duration is estimated from
            // bitrate and length, so allow a frame or two of slack.
            long seconds = decoder.audioInfo().duration().toSeconds();
            assertTrue(seconds >= 6 && seconds <= 8, "duration was " + decoder.audioInfo().duration());

            decoder.seek(Duration.ofSeconds(3));
            byte[] buffer = new byte[SAMPLE_RATE * FRAME_BYTES];
            assertEquals(buffer.length, decoder.read(buffer, 0, buffer.length));
            long position = decoder.framePosition();
            assertTrue(Math.abs(position - 4L * SAMPLE_RATE) < SAMPLE_RATE / 10,
                    "after seeking to 3 s and reading 1 s, position was " + position);

            decoder.seek(Duration.ZERO);
            assertEquals(0L, decoder.framePosition());
            assertEquals(buffer.length, decoder.read(buffer, 0, buffer.length));
        }
    }

    @Test
    void playerSeeksWithinOnlineWavAndFinishes() throws Exception {
        seekThroughPlayer(wavUri, Duration.ofSeconds(2));
    }

    @Test
    void playerSeeksWithinOnlineMp3AndFinishes() throws Exception {
        seekThroughPlayer(mp3Uri, Duration.ofSeconds(6));
    }

    private static void seekThroughPlayer(URI uri, Duration target) throws Exception {
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
            Await.until("stream playing", Duration.ofSeconds(30),
                    () -> player.snapshot().state() == PlaybackState.PLAYING);

            player.pause();
            player.seek(target);
            Await.until("seek on the stream lands", Duration.ofSeconds(30),
                    () -> player.snapshot().state() == PlaybackState.PAUSED
                            && player.snapshot().position().compareTo(target) >= 0);
            Duration landed = player.snapshot().position();
            assertTrue(landed.minus(target).compareTo(Duration.ofSeconds(1)) < 0,
                    "seek to " + target + " landed at " + landed);

            player.resume();
            finished.get(30, TimeUnit.SECONDS);
            assertEquals(PlaybackState.ENDED, player.snapshot().state());
        }
    }

    @Test
    void decoderErrorsDoNotExposeSignedUrlCredentialsOrQueryData() {
        URI signed = URI.create(base + "/unknown?token=abc123#private-fragment");
        UnsupportedAudioFormatException failure = assertThrows(UnsupportedAudioFormatException.class,
                () -> factory.open(MediaLocation.of(signed)));
        assertFalse(failure.getMessage().contains("abc123"), failure.getMessage());
        assertFalse(failure.getMessage().contains("token="), failure.getMessage());
        assertFalse(failure.getMessage().contains("private-fragment"), failure.getMessage());
        assertTrue(failure.getMessage().contains("/unknown"), failure.getMessage());
    }

    @Test
    void reportsHttpErrorStatusFromTheServer() {
        RemoteSourceException error = assertThrows(RemoteSourceException.class,
                () -> factory.open(MediaLocation.of(missingUri)));
        assertEquals(404, error.statusCode());
        assertEquals(missingUri, error.uri());
    }
}
