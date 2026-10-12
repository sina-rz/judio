package io.github.sinaraz.gui;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.audio.SimulatedAudioOutput;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs commands through a real facade with the simulated device; no sound is produced. */
class FacadeActionsTest {

    @Test
    void failedCommandIsReportedAndCloseIsBounded() throws InterruptedException {
        PlaybackConfig config = PlaybackConfig.defaults();
        JudioPlayer player = JudioPlayer.builder().config(config).simulatedOutput().random(new Random(1L)).build();
        BlockingQueue<String> errors = new LinkedBlockingQueue<>();
        FacadeActions actions = new FacadeActions(player, errors::add);

        // Seeking with nothing loaded is an invalid state; the error must come back as a message.
        actions.seek(Duration.ofSeconds(1));
        String message = errors.poll(5, TimeUnit.SECONDS);
        assertNotNull(message, "a failed command must be reported");
        assertTrue(message.startsWith("seek: "), message);

        actions.close();
        // Commands after close are ignored rather than throwing on the caller's thread.
        assertDoesNotThrow(actions::play);
        actions.close();
    }
}
