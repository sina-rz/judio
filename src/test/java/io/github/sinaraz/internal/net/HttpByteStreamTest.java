package io.github.sinaraz.internal.net;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import io.github.sinaraz.audio.StreamOptions;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Loopback tests for the HTTP read-ahead stream: no external network. */
class HttpByteStreamTest {

    private static HttpServer server;
    private static URI uri;
    private static final byte[] BODY = new byte[100_000];

    static {
        for (int i = 0; i < BODY.length; i++) {
            BODY[i] = (byte) (i * 31 % 251);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data.bin", HttpByteStreamTest::serve);
        server.start();
        uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/data.bin");
    }

    @AfterAll
    static void stop() {
        server.stop(0);
    }

    private static void serve(HttpExchange exchange) throws java.io.IOException {
        exchange.getResponseHeaders().set("Accept-Ranges", "bytes");
        String range = exchange.getRequestHeaders().getFirst("Range");
        long from = 0;
        if (range != null && range.startsWith("bytes=")) {
            from = Long.parseLong(range.substring("bytes=".length(), range.indexOf('-')));
        }
        if (from > 0) {
            exchange.getResponseHeaders().set("Content-Range",
                    "bytes " + from + "-" + (BODY.length - 1) + "/" + BODY.length);
            exchange.sendResponseHeaders(206, BODY.length - from);
        } else {
            exchange.sendResponseHeaders(200, BODY.length);
        }
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(BODY, (int) from, BODY.length - (int) from);
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Test
    void readsWholeBodyAndReportsLengthAndRanges() throws Exception {
        try (HttpByteStream in = new HttpByteStream(client(), uri, StreamOptions.defaults())) {
            assertEquals(BODY.length, in.length());
            assertTrue(in.supportsRanges());
            assertArrayEquals(BODY, in.readAllBytes());
        }
    }

    @Test
    void offsetConstructorReadsTheTail() throws Exception {
        try (HttpByteStream in = new HttpByteStream(client(), uri, StreamOptions.defaults(), 60_000L)) {
            assertEquals(BODY.length, in.length(), "length is the whole resource, not the remainder");
            byte[] tail = in.readAllBytes();
            assertEquals(BODY.length - 60_000, tail.length);
            assertArrayEquals(java.util.Arrays.copyOfRange(BODY, 60_000, BODY.length), tail);
        }
    }

    @Test
    void redactionStripsCredentialsQueryAndFragment() {
        URI signed = URI.create("https://user:secret@example.com:8443/a/b.mp3?token=abc123#frag");
        String shown = HttpByteStream.redacted(signed);
        assertEquals("https://example.com:8443/a/b.mp3", shown);
        assertFalse(shown.contains("secret"));
        assertFalse(shown.contains("abc123"));
    }
}
