package io.github.sinaraz;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFormat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.Track;
import io.github.sinaraz.support.RecordingOutput;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** End-to-end coverage through JudioPlayer, DecodeStage, the PCM pipeline, and AudioOutput. */
class OggPlaybackPathTest {

    @TempDir
    Path tempDir;

    private static Stream<Arguments> codecs() {
        return Stream.of(
                Arguments.of("vorbis-mono.ogg", 44_100, 1, 105_840L, "JOrbis"),
                Arguments.of("vorbis-stereo.ogg", 44_100, 2, 105_840L, "JOrbis"),
                Arguments.of("opus-mono.ogg", 48_000, 1, 115_200L, "Concentus"),
                Arguments.of("opus-stereo.ogg", 48_000, 2, 115_200L, "Concentus"));
    }

    @ParameterizedTest
    @MethodSource("codecs")
    void bothOggCodecsReachTheOutputThroughTheRealPlayer(String name, int sampleRate, int channels,
                                                         long frameCount, String decoderName) throws Exception {
        Path file = Files.write(tempDir.resolve(name), fixture(name));
        RecordingOutput output = new RecordingOutput();
        CompletableFuture<AudioInfo> loaded = new CompletableFuture<>();
        CompletableFuture<Track> completed = new CompletableFuture<>();
        List<PlaybackError> errors = new CopyOnWriteArrayList<>();

        try (JudioPlayer player = JudioPlayer.builder().output(() -> output).build()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackLoaded(Track track, AudioInfo info) {
                    loaded.complete(info);
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
            Track track = player.addFiles(List.of(file)).getFirst();
            player.play();

            AudioInfo info = loaded.get(10, TimeUnit.SECONDS);
            assertEquals(sampleRate, info.sampleRate());
            assertEquals(channels, info.channels());
            assertEquals(frameCount, info.frameCount());
            assertEquals(decoderName, info.decoderName());
            assertEquals(track, completed.get(15, TimeUnit.SECONDS));
            assertEquals(PlaybackState.ENDED, player.snapshot().state());
        }

        byte[] pcm = output.recorded();
        assertEquals(frameCount * 2L * 2L, pcm.length,
                "the playback pipeline must write every frame once as stereo PCM16");
        assertTrue(output.format() != null);
        assertEquals(sampleRate, (int) output.format().getSampleRate());
        assertEquals(2, output.format().getChannels(), "Judio's post-decode pipeline always outputs stereo");
        assertTrue(maxAbsSample(pcm) > 1000, "the recorded player output must contain the decoded tone");
        assertTrue(errors.isEmpty(), errors.toString());
    }

    private byte[] fixture(String name) throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/audio/ogg/" + name)) {
            if (in == null) {
                throw new IOException("missing Ogg test fixture: " + name);
            }
            return in.readAllBytes();
        }
    }

    private static int maxAbsSample(byte[] pcm) {
        int maximum = 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) {
            int sample = (short) ((pcm[i] & 0xFF) | ((pcm[i + 1] & 0xFF) << 8));
            maximum = Math.max(maximum, Math.abs(sample));
        }
        return maximum;
    }
}
