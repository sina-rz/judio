package io.github.sinaraz.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates silent MPEG-1 Layer III files without an encoder: valid frame
 * headers followed by zeroed main data, which JLayer decodes as silence.
 * Frame size for 128 kbps at 44.1 kHz is {@code 144 * 128000 / 44100 = 417}
 * bytes (no padding), and every frame carries 1152 samples per channel, so
 * durations and frame positions are exact and assertable.
 */
public final class TestMp3 {

    public static final int SAMPLE_RATE = 44_100;
    public static final int CHANNELS = 2;
    public static final int FRAME_BYTES = 417;
    public static final int SAMPLES_PER_FRAME = 1152;

    private TestMp3() {}

    /** Bytes of a silent stereo MP3 with {@code frames} MPEG frames. */
    public static byte[] silentMp3(int frames) {
        byte[] out = new byte[frames * FRAME_BYTES];
        for (int f = 0; f < frames; f++) {
            int o = f * FRAME_BYTES;
            out[o] = (byte) 0xFF;
            out[o + 1] = (byte) 0xFB; // sync + MPEG-1 + Layer III + no CRC
            out[o + 2] = (byte) 0x90; // 128 kbps, 44100 Hz
            out[o + 3] = 0x00;        // stereo
        }
        return out;
    }

    /** Writes a silent stereo MP3 file. */
    public static Path writeSilentMp3(Path file, int frames) throws IOException {
        Files.write(file, silentMp3(frames));
        return file;
    }
}
