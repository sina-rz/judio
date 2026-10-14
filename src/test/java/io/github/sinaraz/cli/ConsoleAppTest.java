package io.github.sinaraz.cli;

import java.io.BufferedReader;
import java.io.PrintStream;
import java.io.StringReader;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.support.TestAudio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleAppTest {

    @TempDir
    Path dir;

    @Test
    void failedLoadKeepsTheExistingQueue() throws Exception {
        Path wav = TestAudio.writeWav(dir.resolve("keep.wav"), 800, 1, 8000, 16);
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        try (JudioPlayer player = JudioPlayer.builder().simulatedOutput().build()) {
            ConsoleApp app = new ConsoleApp(player,
                    new PrintStream(captured, true, StandardCharsets.UTF_8),
                    new BufferedReader(new StringReader("")));
            player.addFiles(wav);
            assertEquals(1, player.queue().size());

            app.dispatch("load " + dir.resolve("missing.wav"));
            assertEquals(1, player.queue().size(),
                    "a failed 'load' must not clear the queue first");
            assertTrue(captured.toString(StandardCharsets.UTF_8).contains("error"),
                    captured.toString(StandardCharsets.UTF_8));
        }
    }
}
