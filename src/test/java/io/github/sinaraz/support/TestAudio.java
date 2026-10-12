package io.github.sinaraz.support;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

/** Generates small PCM fixtures at test time, so no binary audio files are committed. */
public final class TestAudio {

    private TestAudio() {}

    /** Sine tone as signed PCM with {@code bits} per sample, little or big endian. */
    public static byte[] tone(int frames, int channels, int sampleRate, int bits, boolean bigEndian,
                              double frequency, double amplitude) {
        int bytes = bits / 8;
        byte[] out = new byte[frames * channels * bytes];
        double max = (1L << (bits - 1)) - 1;
        int p = 0;
        for (int f = 0; f < frames; f++) {
            double v = amplitude * Math.sin(2 * Math.PI * frequency * f / sampleRate);
            long sample = Math.round(v * max);
            for (int c = 0; c < channels; c++) {
                for (int k = 0; k < bytes; k++) {
                    int shift = bigEndian ? (bytes - 1 - k) * 8 : k * 8;
                    out[p++] = (byte) (sample >>> shift);
                }
            }
        }
        return out;
    }

    /** Writes a tone as WAV (little-endian PCM). */
    public static Path writeWav(Path file, int frames, int channels, int sampleRate, int bits) throws IOException {
        return write(file, AudioFileType.WAVE, frames, channels, sampleRate, bits, false);
    }

    /** Writes a tone as AIFF (big-endian PCM). */
    public static Path writeAiff(Path file, int frames, int channels, int sampleRate, int bits) throws IOException {
        return write(file, AudioFileType.AIFF, frames, channels, sampleRate, bits, true);
    }

    /** Writes a tone as Sun/NeXT AU (big-endian PCM). */
    public static Path writeAu(Path file, int frames, int channels, int sampleRate, int bits) throws IOException {
        return write(file, AudioFileType.AU, frames, channels, sampleRate, bits, true);
    }

    private static Path write(Path file, AudioFileType type, int frames, int channels, int sampleRate, int bits,
                              boolean bigEndian) throws IOException {
        byte[] pcm = tone(frames, channels, sampleRate, bits, bigEndian, 440.0, 0.5);
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, bits, channels,
                channels * bits / 8, sampleRate, bigEndian);
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(ais, type.javaType, file.toFile());
        }
        return file;
    }

    private enum AudioFileType {
        WAVE(javax.sound.sampled.AudioFileFormat.Type.WAVE),
        AIFF(javax.sound.sampled.AudioFileFormat.Type.AIFF),
        AU(javax.sound.sampled.AudioFileFormat.Type.AU);

        final javax.sound.sampled.AudioFileFormat.Type javaType;

        AudioFileType(javax.sound.sampled.AudioFileFormat.Type javaType) {
            this.javaType = javaType;
        }
    }

    /** Temporary file helper that keeps test output tidy. */
    public static Path tempFile(Path dir, String name, byte[] content) throws IOException {
        Path p = dir.resolve(name);
        Files.write(p, content);
        return p;
    }
}
