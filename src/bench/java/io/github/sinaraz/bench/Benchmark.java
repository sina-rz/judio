package io.github.sinaraz.bench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Random;

import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.audio.AudioDecoder;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.internal.processing.PlaybackPipeline;


public final class Benchmark {

    private static final int SEEKS = 25;
    private static final int CHUNK = PlaybackConfig.defaults().chunkBytes();

    static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: Benchmark <audio file> [...]");
            System.exit(2);
        }
        for (String arg : args) {
            run(Path.of(arg));
        }
    }

    private static void run(Path file) throws Exception {
        System.out.printf("%n== %s (%d MB)%n", file.getFileName(), Files.size(file) / (1024 * 1024));
        long openStart = System.nanoTime();
        AudioDecoder decoder = DecoderFactory.standard().open(file);
        double openMs = (System.nanoTime() - openStart) / 1e6;
        double seconds = decoder.audioInfo().duration().toMillis() / 1000.0;
        System.out.printf("duration %.1f s, open %.1f ms (%s)%n", seconds, openMs,
                decoder.audioInfo().decoderName());

        Random warm = new Random(7L);
        for (int i = 0; i < 30; i++) {
            decoder.seek(Duration.ofMillis((long) (warm.nextDouble() * seconds * 1000)));
            decoder.read(new byte[CHUNK], 0, CHUNK);
        }
        Random random = new Random(42L);
        long[] seekNanos = new long[SEEKS];
        for (int i = 0; i < SEEKS; i++) {
            Duration target = Duration.ofMillis((long) (random.nextDouble() * seconds * 1000));
            long start = System.nanoTime();
            decoder.seek(target);
            seekNanos[i] = System.nanoTime() - start;
            decoder.read(new byte[CHUNK], 0, CHUNK);
        }
        Arrays.sort(seekNanos);
        System.out.printf("seek over %d random targets: median %.2f ms, p90 %.2f ms, max %.2f ms%n",
                SEEKS, seekNanos[SEEKS / 2] / 1e6, seekNanos[SEEKS * 9 / 10] / 1e6, seekNanos[SEEKS - 1] / 1e6);
        decoder.close();

        for (int pass = 0; pass < 2; pass++) {
            boolean withPipeline = pass == 1;
            double best = 0;
            for (int rep = 0; rep < 3; rep++) {
                best = Math.max(best, decodeSpeed(file, withPipeline, seconds));
            }
            System.out.printf("decode %s: %.0fx realtime (best of 3)%n",
                    withPipeline ? "+ pipeline (speed 1.0)" : "only      ", best);
        }
    }

    private static double decodeSpeed(Path file, boolean withPipeline, double seconds) throws Exception {
        PlaybackPipeline pipeline = new PlaybackPipeline();
        byte[] buffer = new byte[CHUNK];
        long start = System.nanoTime();
        try (AudioDecoder decoder = DecoderFactory.standard().open(file)) {
            int channels = decoder.audioInfo().channels();
            long sink = 0;
            int n;
            while ((n = decoder.read(buffer, 0, buffer.length)) >= 0) {
                if (withPipeline) {
                    sink += pipeline.process(buffer, n, channels, 1.0, false, 1.0f, 0.0f);
                }
            }
            if (sink == -1) {
                System.out.println(sink);
            }
        }
        double elapsed = (System.nanoTime() - start) / 1e9;
        return seconds / elapsed;
    }
}
