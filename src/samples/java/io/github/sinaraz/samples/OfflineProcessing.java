package io.github.sinaraz.samples;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.internal.processing.PlaybackPipeline;

/**
 * Runs the processing pipeline offline: decode, change speed, gain, and pan, then write the result
 * as a WAV file. The pipeline is the same one the engine uses on its worker thread.
 */
public final class OfflineProcessing {


    static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("offline");
        Path input = args.length > 0 ? Path.of(args[0]) : SampleSupport.writeToneWav(dir, "in.wav", 2.0);
        Path output = dir.resolve("processed.wav");

        byte[] processed;
        try (AudioDecoder decoder = DecoderFactory.standard().open(input)) {
            int channels = decoder.audioInfo().channels();
            PlaybackPipeline pipeline = new PlaybackPipeline();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = decoder.read(chunk, 0, chunk.length)) >= 0) {
                int produced = pipeline.process(chunk, n, channels, 0.75, false, 0.5f, -0.5f);
                out.write(pipeline.output(), 0, produced);   // copy now: the buffer is reused
            }
            int tail = pipeline.finish(0.5f, -0.5f);
            out.write(pipeline.output(), 0, tail);
            processed = out.toByteArray();
        }

        // Write the result as a 16-bit stereo WAV so it can be opened in any audio editor.
        javax.sound.sampled.AudioFormat format =
                new javax.sound.sampled.AudioFormat(SampleSupport.SAMPLE_RATE, 16, 2, true, false);
        try (javax.sound.sampled.AudioInputStream stream = new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(processed), format, processed.length / 4)) {
            javax.sound.sampled.AudioSystem.write(stream,
                    javax.sound.sampled.AudioFileFormat.Type.WAVE, output.toFile());
        }
        System.out.printf("wrote %s (%d bytes of PCM, speed 0.75x, gain 0.5, pan left)%n", output,
                processed.length);
    }
}
