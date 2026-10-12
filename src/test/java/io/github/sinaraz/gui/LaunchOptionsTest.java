package io.github.sinaraz.gui;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOptionsTest {

    @Test
    void defaultsAreRealAudioAndNoFiles() {
        LaunchOptions options = LaunchOptions.parse(new String[0]);
        assertFalse(options.simulatedAudio());
        assertFalse(options.help());
        assertEquals(List.of(), options.files());
    }

    @Test
    void parsesFlagsAndFiles() {
        LaunchOptions options = LaunchOptions.parse(new String[] {"--null-audio", "a.mp3", "b.wav"});
        assertTrue(options.simulatedAudio());
        assertEquals(List.of(Path.of("a.mp3"), Path.of("b.wav")), options.files());
    }

    @Test
    void recognisesHelp() {
        assertTrue(LaunchOptions.parse(new String[] {"-h"}).help());
        assertTrue(LaunchOptions.parse(new String[] {"--help"}).help());
    }

    @Test
    void rejectsUnknownOptions() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> LaunchOptions.parse(new String[] {"--bogus"}));
        assertTrue(e.getMessage().contains("--bogus"));
    }
}
