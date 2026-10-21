package io.github.sinaraz.audio;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.Track;
import io.github.sinaraz.support.RecordingOutput;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises HTTP/HTTPS Ogg decoding, byte-range seeks, and the remote seek path through JudioPlayer. */
class OggHttpStreamTest {

    private static final char[] TEST_PASSWORD = "judio-test".toCharArray();
    private static final String KEY_ALIAS = "judio-test-server";
    private static HttpServer httpServer;
    private static HttpsServer httpsServer;
    private static int httpPort;
    private static int httpsPort;
    private static final CopyOnWriteArrayList<String> rangeRequests = new CopyOnWriteArrayList<>();
    private static byte[] vorbisMono;
    private static byte[] vorbisStereo;
    private static byte[] opusMono;
    private static byte[] opusStereo;
    private static StreamOptions streamOptions;
    private static ExecutorService serverExecutor;
    private static byte[] largeOgg;

    @BeforeAll
    static void startServers() throws Exception {
        vorbisMono = resource("vorbis-mono.ogg");
        vorbisStereo = resource("vorbis-stereo.ogg");
        opusMono = resource("opus-mono.ogg");
        opusStereo = resource("opus-stereo.ogg");
        largeOgg = new byte[opusMono.length * 32];
        for (int i = 0; i < 32; i++) {
            System.arraycopy(opusMono, 0, largeOgg, i * opusMono.length, opusMono.length);
        }

        KeyStore serverKeys = loadTestKeyStore();
        SSLContext serverContext = serverContext(serverKeys);
        SSLContext clientContext = clientContext(serverKeys.getCertificate(KEY_ALIAS));
        streamOptions = StreamOptions.builder()
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(5))
                .bufferBytes(StreamOptions.MIN_BUFFER_BYTES)
                .maxRetries(0)
                .sslContext(clientContext)
                .build();

        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.setExecutor(serverExecutor);
        installContexts(httpServer);
        httpServer.start();
        httpPort = httpServer.getAddress().getPort();

        httpsServer = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        httpsServer.setExecutor(serverExecutor);
        installContexts(httpsServer);
        httpsServer.start();
        httpsPort = httpsServer.getAddress().getPort();
    }

    @AfterAll
    static void stopServers() {
        if (httpServer != null) {
            httpServer.stop(0);
        }
        if (httpsServer != null) {
            httpsServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.close();
        }
    }

    private static Stream<Arguments> remoteFixtures() {
        return Stream.of(
                Arguments.of("http", "vorbis-mono.ogg", 44_100, 1, 105_840L),
                Arguments.of("http", "vorbis-stereo.ogg", 44_100, 2, 105_840L),
                Arguments.of("http", "opus-mono.ogg", 48_000, 1, 115_200L),
                Arguments.of("http", "opus-stereo.ogg", 48_000, 2, 115_200L),
                Arguments.of("https", "vorbis-mono.ogg", 44_100, 1, 105_840L),
                Arguments.of("https", "vorbis-stereo.ogg", 44_100, 2, 105_840L),
                Arguments.of("https", "opus-mono.ogg", 48_000, 1, 115_200L),
                Arguments.of("https", "opus-stereo.ogg", 48_000, 2, 115_200L));
    }

    @ParameterizedTest
    @MethodSource("remoteFixtures")
    void opensAndSeeksOggIncrementallyFromHttpAndHttpsMediaLocations(String scheme, String name,
                                                                     int sampleRate, int channels,
                                                                     long frames) throws Exception {
        int port = scheme.equals("https") ? httpsPort : httpPort;
        URI uri = URI.create(scheme + "://127.0.0.1:" + port + "/" + name);
        int rangesBefore = rangeRequests.size();
        try (AudioDecoder decoder = DecoderFactory.standard(streamOptions).open(MediaLocation.of(uri))) {
            assertEquals(sampleRate, decoder.audioInfo().sampleRate());
            assertEquals(channels, decoder.audioInfo().channels());
            assertEquals(Duration.ofMillis(2400), decoder.audioInfo().duration());
            assertEquals(frames, decoder.audioInfo().frameCount());
            assertTrue(decoder.audioInfo().seekable());
            assertTrue(decoder.isSeekable());
            byte[] pcm = readAll(decoder);
            assertEquals(frames * channels * 2L, pcm.length);
            assertEquals(frames, decoder.framePosition());

            long targetFrame = sampleRate * 23L / 10L;
            decoder.seek(Duration.ofMillis(2300));
            assertEquals(targetFrame, decoder.framePosition());
            int frameSize = channels * 2;
            byte[] afterSeek = readN(decoder, 8192 - (8192 % frameSize));
            int startByte = Math.toIntExact(targetFrame * frameSize);
            assertArrayEquals(java.util.Arrays.copyOfRange(pcm, startByte,
                    startByte + afterSeek.length), afterSeek,
                    "range seeking must reproduce the same PCM as sequential decoding");
            assertTrue(rangeRequests.subList(rangesBefore, rangeRequests.size()).stream()
                            .anyMatch(OggHttpStreamTest::isNonzeroRangeRequest),
                    "remote Ogg seek must issue a nonzero HTTP Range request");
            if (name.startsWith("opus")) {
                assertTrue(rangeRequests.subList(rangesBefore, rangeRequests.size()).stream()
                                .anyMatch(request -> rangeStartOffset(request) > 8_000),
                        "a late Opus seek should start at an interior audio-page offset");
            }

            decoder.seek(Duration.ZERO);
            assertEquals(0, decoder.framePosition());
            assertArrayEquals(java.util.Arrays.copyOf(pcm, 4096), readN(decoder, 4096));
        }
    }

    @Test
    void remoteOggWithoutRangeSupportRemainsPlayableButNotSeekable() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + httpPort + "/no-range.ogg");
        try (AudioDecoder decoder = DecoderFactory.standard(streamOptions).open(MediaLocation.of(uri))) {
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertFalse(decoder.audioInfo().seekable());
            assertFalse(decoder.isSeekable());
            assertThrows(SeekNotSupportedException.class, () -> decoder.seek(Duration.ofSeconds(1)));
            assertTrue(readAll(decoder).length > 0);
        }
    }

    @Test
    void chunkedOggWithoutContentLengthKeepsUnknownDurationAndDoesNotProbeRanges() throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + httpPort + "/unknown-length.ogg");
        int rangesBefore = rangeRequests.size();
        try (AudioDecoder decoder = DecoderFactory.standard(streamOptions).open(MediaLocation.of(uri))) {
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertFalse(decoder.isSeekable());
            assertEquals(115_200L * 2, readAll(decoder).length);
        }
        assertEquals(rangesBefore, rangeRequests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http", "https"})
    void refusedOptionalTailRequestDoesNotFailAnOtherwisePlayableOgg(String scheme) throws Exception {
        int port = scheme.equals("https") ? httpsPort : httpPort;
        URI uri = URI.create(scheme + "://127.0.0.1:" + port + "/range-refused.ogg");
        int rangesBefore = rangeRequests.size();
        try (AudioDecoder decoder = DecoderFactory.standard(streamOptions).open(MediaLocation.of(uri))) {
            assertEquals(Duration.ZERO, decoder.audioInfo().duration());
            assertEquals(0, decoder.audioInfo().frameCount());
            assertTrue(readN(decoder, 8192).length > 0);
            decoder.seek(Duration.ofSeconds(1));
            assertEquals(48_000, decoder.framePosition());
            assertTrue(readN(decoder, 8192).length > 0);
        }
        assertTrue(rangeRequests.subList(rangesBefore, rangeRequests.size()).stream()
                .anyMatch(OggHttpStreamTest::isNonzeroRangeRequest), "the optional tail request must be exercised");
    }

    private static Stream<Arguments> playerFixtures() {
        return Stream.of(
                Arguments.of("http", "vorbis-mono.ogg", 44_100, 105_840L),
                Arguments.of("https", "opus-mono.ogg", 48_000, 115_200L));
    }

    @ParameterizedTest
    @MethodSource("playerFixtures")
    void playerCanSeekRemoteVorbisAndOpusThroughHttpAndHttps(String scheme, String name,
                                                             int sampleRate, long frames) throws Exception {
        int port = scheme.equals("https") ? httpsPort : httpPort;
        URI uri = URI.create(scheme + "://127.0.0.1:" + port + "/" + name);
        RecordingOutput output = new RecordingOutput();
        CompletableFuture<AudioInfo> loaded = new CompletableFuture<>();
        CompletableFuture<Track> completed = new CompletableFuture<>();
        CountDownLatch seekFinished = new CountDownLatch(1);
        AtomicBoolean sawSeeking = new AtomicBoolean();
        CopyOnWriteArrayList<PlaybackError> errors = new CopyOnWriteArrayList<>();

        try (JudioPlayer player = JudioPlayer.builder()
                .output(() -> output)
                .decoders(DecoderFactory.standard(streamOptions))
                .build()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackLoaded(Track track, AudioInfo info) {
                    loaded.complete(info);
                }

                @Override
                public void onStateChanged(PlaybackSnapshot snapshot) {
                    if (snapshot.state() == PlaybackState.SEEKING) {
                        sawSeeking.set(true);
                    } else if (sawSeeking.get() && snapshot.state() == PlaybackState.READY) {
                        seekFinished.countDown();
                    }
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    completed.complete(track);
                }

                @Override
                public void onError(PlaybackError error) {
                    errors.add(error);
                    completed.completeExceptionally(new AssertionError(error.message()));
                }
            });
            Track track = player.addUrl(uri);
            player.prepare(0);
            AudioInfo info = loaded.get(10, TimeUnit.SECONDS);
            assertEquals(sampleRate, info.sampleRate());
            assertEquals(Duration.ofMillis(2400), info.duration());
            assertEquals(frames, info.frameCount());
            assertEquals(info.duration(), player.snapshot().duration());
            assertTrue(info.seekable());

            player.seek(Duration.ofSeconds(1));
            assertTrue(seekFinished.await(10, TimeUnit.SECONDS), "player seek should complete");
            assertEquals(Duration.ofSeconds(1), player.snapshot().position());

            player.play();
            assertEquals(track, completed.get(15, TimeUnit.SECONDS));
            assertEquals(PlaybackState.ENDED, player.snapshot().state());
        }

        assertEquals((frames - sampleRate) * 4L, output.recorded().length,
                "player output should begin at the requested frame and remain stereo PCM16");
        assertTrue(errors.isEmpty(), errors.toString());
    }

    private static void installContexts(HttpServer server) {
        server.createContext("/vorbis-mono.ogg", exchange -> serve(exchange, vorbisMono));
        server.createContext("/vorbis-stereo.ogg", exchange -> serve(exchange, vorbisStereo));
        server.createContext("/opus-mono.ogg", exchange -> serve(exchange, opusMono));
        server.createContext("/opus-stereo.ogg", exchange -> serve(exchange, opusStereo));
        server.createContext("/no-range.ogg", exchange -> serveWithoutRanges(exchange, opusMono));
        server.createContext("/unknown-length.ogg", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/ogg");
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            exchange.sendResponseHeaders(200, 0); // chunked response with no Content-Length
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(opusMono);
            }
        });
        server.createContext("/range-refused.ogg", exchange -> {
            exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
            String range = exchange.getRequestHeaders().getFirst("Range");
            if (range != null) {
                rangeRequests.add(exchange.getRequestURI().getPath() + ":" + range);
                exchange.sendResponseHeaders(403, -1);
                exchange.close();
            } else {
                serveWithoutRanges(exchange, largeOgg);
            }
        });
    }

    private static void serveWithoutRanges(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "audio/ogg");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void serve(HttpExchange exchange, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "audio/ogg");
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        if ("HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(body.length));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            return;
        }

        String range = exchange.getRequestHeaders().getFirst("Range");
        int start = 0;
        if (range != null) {
            rangeRequests.add(exchange.getRequestURI().getPath() + ":" + range);
            if (!range.startsWith("bytes=") || !range.endsWith("-")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            try {
                start = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
            } catch (NumberFormatException e) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            if (start < 0 || start >= body.length) {
                exchange.getResponseHeaders().set("Content-Range", "bytes */" + body.length);
                exchange.sendResponseHeaders(416, -1);
                exchange.close();
                return;
            }
            exchange.getResponseHeaders().set("Content-Range", "bytes " + start + "-" + (body.length - 1)
                    + "/" + body.length);
        }
        int status = range == null ? 200 : 206;
        int count = body.length - start;
        exchange.sendResponseHeaders(status, count);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body, start, count);
        }
    }

    private static boolean isNonzeroRangeRequest(String request) {
        return rangeStartOffset(request) > 0;
    }

    private static long rangeStartOffset(String request) {
        int prefix = request.indexOf("bytes=");
        if (prefix < 0) {
            return -1L;
        }
        int start = prefix + "bytes=".length();
        int dash = request.indexOf('-', start);
        if (dash < 0) {
            return -1L;
        }
        try {
            return Long.parseLong(request.substring(start, dash));
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static byte[] readAll(AudioDecoder decoder) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16_384];
        int count;
        while ((count = decoder.read(buffer, 0, buffer.length)) >= 0) {
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static byte[] readN(AudioDecoder decoder, int wanted) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(wanted);
        byte[] buffer = new byte[Math.min(4096, wanted)];
        while (out.size() < wanted) {
            int request = Math.min(buffer.length, wanted - out.size());
            int count = decoder.read(buffer, 0, request);
            if (count < 0) {
                throw new IOException("unexpected end of Ogg stream after " + out.size() + " of " + wanted + " bytes");
            }
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = OggHttpStreamTest.class.getResourceAsStream("/audio/ogg/" + name)) {
            if (in == null) {
                throw new IOException("missing Ogg test fixture: " + name);
            }
            return in.readAllBytes();
        }
    }

    private static KeyStore loadTestKeyStore() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = OggHttpStreamTest.class.getResourceAsStream("/audio/ogg/test-server.p12")) {
            if (in == null) {
                throw new IOException("missing test HTTPS keystore");
            }
            keyStore.load(in, TEST_PASSWORD);
        }
        return keyStore;
    }

    private static SSLContext serverContext(KeyStore keyStore) throws Exception {
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keyStore, TEST_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, new SecureRandom());
        return context;
    }

    private static SSLContext clientContext(java.security.cert.Certificate certificate) throws Exception {
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry(KEY_ALIAS, certificate);
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, managers.getTrustManagers(), new SecureRandom());
        return context;
    }
}
