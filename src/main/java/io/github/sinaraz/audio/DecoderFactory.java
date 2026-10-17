package io.github.sinaraz.audio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;

import io.github.sinaraz.error.UnsupportedAudioFormatException;
import io.github.sinaraz.internal.decoder.FormatSniffer;
import io.github.sinaraz.internal.decoder.JavaSoundDecoder;
import io.github.sinaraz.internal.decoder.Mp3Decoder;
import io.github.sinaraz.internal.decoder.PcmFileDecoder;
import io.github.sinaraz.internal.decoder.StreamingMp3Decoder;
import io.github.sinaraz.internal.decoder.StreamingPcmDecoder;
import io.github.sinaraz.internal.net.HttpByteStream;
import io.github.sinaraz.internal.ogg.OggAudioDecoder;
import io.github.sinaraz.model.MediaLocation;

/**
 * Chooses a decoder for a location based on its content.
 *
 * <p>Supported containers in this build:
 * <ul>
 *   <li>MP3 (MPEG-1/2/2.5 Layers I, II and III) via {@link Mp3Decoder} (JLayer). Remote MP3 streams use a
 *       sequential decoder without seeking.</li>
 *   <li>WAV, AIFF, AU (including µ-law and A-law) via {@link JavaSoundDecoder} (built-in Java Sound).
 *       Remote streams use a sequential Java Sound decoder.</li>
 *   <li>Ogg Opus via Concentus and Ogg Vorbis via JOrbis, both pure Java. Local files are indexed for
 *       exact granule-based duration and reset-and-decode seeking; remote Ogg is incremental, has
 *       unknown duration, and uses HTTP byte ranges plus codec preroll for seeking when supported.</li>
 *   <li>{@code http} and {@code https} streams, fetched with {@link java.net.http.HttpClient}, with
 *       read-ahead and resume after dropped connections. See {@link StreamOptions}.</li>
 * </ul>
 * FLAC is detected but rejected with {@link UnsupportedAudioFormatException}; no verified pure-Java
 * FLAC decoder is bundled.
 *
 * <p>A factory holds one HTTP client, created on first use. It can be shared between players.
 */
public final class DecoderFactory implements DecoderProvider {

    private final StreamOptions streamOptions;
    private HttpClient client; // created lazily, guarded by this

    private DecoderFactory(StreamOptions streamOptions) {
        this.streamOptions = Objects.requireNonNull(streamOptions, "streamOptions");
    }

    /**
     * Creates the default factory with {@link StreamOptions#defaults()} for streams.
     *
     * @return a new factory
     */
    public static DecoderFactory standard() {
        return new DecoderFactory(StreamOptions.defaults());
    }

    /**
     * Creates a factory whose streams use the given options.
     *
     * @param streamOptions timeouts, buffering, retries, headers, and TLS for {@code http} and
     *                      {@code https} sources
     * @return a new factory
     */
    public static DecoderFactory standard(StreamOptions streamOptions) {
        return new DecoderFactory(streamOptions);
    }

    /**
     * Opens a decoder for {@code location}.
     *
     * @throws NoSuchFileException                 if a local file does not exist
     * @throws UnsupportedAudioFormatException     if no decoder handles the content
     * @throws io.github.sinaraz.error.RemoteSourceException if a stream cannot be opened
     * @throws io.github.sinaraz.error.DecoderException if the source is recognised but cannot be decoded
     */
    @Override
    public AudioDecoder open(MediaLocation location) throws IOException {
        Objects.requireNonNull(location, "location");
        return switch (location) {
            case MediaLocation.LocalFile local -> openLocal(local.path());
            case MediaLocation.Remote remote -> openRemote(remote.uri());
        };
    }

    private AudioDecoder openLocal(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new NoSuchFileException(file.toString());
        }
        if (!Files.isRegularFile(file)) {
            throw new UnsupportedAudioFormatException("open", "not a regular file: " + file, file, null);
        }
        if (!Files.isReadable(file)) {
            throw new AccessDeniedException(file.toString());
        }
        return switch (FormatSniffer.detect(file)) {
            case MP3 -> new Mp3Decoder(file);
            case WAVE, AIFF, AU -> openPcm(file);
            case FLAC -> throw new UnsupportedAudioFormatException("open",
                    "FLAC is not supported in this build: no verified pure-Java FLAC decoder is bundled",
                    file, null);
            case OGG -> OggAudioDecoder.openLocal(file);
            case UNKNOWN -> openUnknown(file);
        };
    }

    /**
     * Uses the native reader for linear PCM (fast, O(1) seeks) and Java Sound for everything else,
     * such as µ-law, float or extensible WAVE files.
     */
    private AudioDecoder openPcm(Path file) throws IOException {
        try {
            return new PcmFileDecoder(file);
        } catch (UnsupportedAudioFormatException e) {
            return new JavaSoundDecoder(file);
        }
    }

    private AudioDecoder openUnknown(Path file) throws IOException {
        try {
            return new JavaSoundDecoder(file);
        } catch (UnsupportedAudioFormatException e) {
            throw new UnsupportedAudioFormatException("open",
                    "unrecognised audio format: " + file.getFileName(), file, e);
        }
    }

    private AudioDecoder openRemote(URI uri) throws IOException {
        HttpByteStream raw = new HttpByteStream(httpClient(), uri, streamOptions);
        PushbackInputStream probe = new PushbackInputStream(raw, 16);
        try {
            byte[] head = new byte[16];
            int n = probe.readNBytes(head, 0, head.length);
            probe.unread(head, 0, n);
            InputStream buffered = new BufferedInputStream(probe, 64 * 1024);
            return switch (FormatSniffer.detect(head, n)) {
                case MP3 -> new StreamingMp3Decoder(buffered, uri.toString(), raw.length(),
                        offset -> new HttpByteStream(httpClient(), uri, streamOptions, offset));
                case WAVE, AIFF, AU -> new StreamingPcmDecoder(buffered, uri.toString(), raw.length(),
                        offset -> new HttpByteStream(httpClient(), uri, streamOptions, offset));
                case FLAC -> throw new UnsupportedAudioFormatException("open",
                        "FLAC streams are not supported in this build: " + uri, null, null);
                case OGG -> OggAudioDecoder.openRemote(buffered, safeOggSource(uri), raw.length(),
                        raw.supportsRanges()
                                ? offset -> new BufferedInputStream(
                                        new HttpByteStream(httpClient(), uri, streamOptions, offset), 64 * 1024)
                                : null);
                case UNKNOWN -> throw new UnsupportedAudioFormatException("open",
                        "unrecognised audio stream: " + uri, null, null);
            };
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    private static String safeOggSource(URI uri) {
        String host = uri.getHost();
        return host == null ? "remote Ogg stream" : uri.getScheme() + "://" + host;
    }

    private synchronized HttpClient httpClient() {
        if (client == null) {
            HttpClient.Builder builder = HttpClient.newBuilder()
                    .connectTimeout(streamOptions.connectTimeout())
                    .followRedirects(HttpClient.Redirect.NORMAL);
            if (streamOptions.sslContext() != null) {
                builder.sslContext(streamOptions.sslContext());
            }
            client = builder.build();
        }
        return client;
    }
}
