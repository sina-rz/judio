package io.github.sinaraz.samples;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.model.AudioInfo;

/**
 * Uses the decoder layer directly, with no engine and no output device: reads PCM, seeks, and
 * reports the peak level. Useful for analysis or for building your own processing.
 */
public final class DecodeOffline {


    static void main(String[] args) throws Exception {
        Path file = args.length > 0 ? Path.of(args[0])
                : SampleSupport.writeToneWav(Files.createTempDirectory("decode"), "tone.wav", 3.0);

        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            AudioInfo info = decoder.audioInfo();
            System.out.printf("%s: %s, %s, %d Hz, %d ch, %d frames%n", file.getFileName(), info.decoderName(),
                    info.sourceFormat(), info.sampleRate(), info.channels(), info.frameCount());

            System.out.printf("whole file peak: %d%n", peak(decoder));

            decoder.seek(Duration.ofSeconds(1));
            System.out.printf("peak of the second starting at 1 s: %d (frame %d)%n",
                    peakForSeconds(decoder, 1), decoder.framePosition());
        }
    }

    /** Peak absolute 16-bit sample value from the current position to the end. */
    private static int peak(AudioDecoder decoder) throws IOException {
        byte[] buffer = new byte[8192];
        int peak = 0;
        int n;
        while ((n = decoder.read(buffer, 0, buffer.length)) >= 0) {
            for (int i = 0; i + 1 < n; i += 2) {
                int sample = (short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8));
                peak = Math.max(peak, Math.abs(sample));
            }
        }
        return peak;
    }

    private static int peakForSeconds(AudioDecoder decoder, int seconds) throws IOException {
        int bytes = seconds * decoder.audioInfo().sampleRate() * decoder.audioInfo().frameSize();
        byte[] buffer = new byte[bytes];
        int total = 0;
        int n;
        while (total < bytes && (n = decoder.read(buffer, total, bytes - total)) >= 0) {
            total += n;
        }
        int peak = 0;
        for (int i = 0; i + 1 < total; i += 2) {
            peak = Math.max(peak, Math.abs((short) ((buffer[i] & 0xFF) | (buffer[i + 1] << 8))));
        }
        return peak;
    }
}
