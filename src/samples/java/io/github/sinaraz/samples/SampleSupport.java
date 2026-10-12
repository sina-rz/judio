package io.github.sinaraz.samples;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Helpers shared by the samples: they create their own test audio so no media files are needed. */
final class SampleSupport {

    static final int SAMPLE_RATE = 44_100;


    /** Writes a stereo 16-bit WAV with a 440 Hz tone on the left and 660 Hz on the right. */
    static Path writeToneWav(Path dir, String name, double seconds) throws IOException {
        Files.createDirectories(dir);
        int frames = (int) (seconds * SAMPLE_RATE);
        byte[] pcm = new byte[frames * 4];
        for (int f = 0; f < frames; f++) {
            short left = (short) (Math.sin(2 * Math.PI * 440.0 * f / SAMPLE_RATE) * 12_000);
            short right = (short) (Math.sin(2 * Math.PI * 660.0 * f / SAMPLE_RATE) * 12_000);
            pcm[4 * f] = (byte) left;
            pcm[4 * f + 1] = (byte) (left >> 8);
            pcm[4 * f + 2] = (byte) right;
            pcm[4 * f + 3] = (byte) (right >> 8);
        }
        AudioFormat format = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
        Path file = dir.resolve(name);
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(stream, javax.sound.sampled.AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }
}
