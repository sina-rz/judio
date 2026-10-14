package io.github.sinaraz.internal.decoder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A seek on an 8-bit WAV stream must convert the target frame to a byte offset
 * with the SOURCE frame size (1 byte), not the canonical 16-bit frame size.
 */
class StreamingPcmSeekTest {

    @Test
    void eightBitStreamSeeksBySourceFrameSize() throws Exception {
        int rate = 8000;
        int frames = rate * 3;
        byte[] pcm = new byte[frames];  // unsigned 8-bit mono silence-ish ramp
        for (int i = 0; i < frames; i++) {
            pcm[i] = (byte) (128 + (i % 64) - 32);
        }
        AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_UNSIGNED, rate, 8, 1, 1, rate, false);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (AudioInputStream ais = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, bytes);
        }
        byte[] wav = bytes.toByteArray();

        AtomicLong reopenedAt = new AtomicLong(-1);
        RangeOpener opener = offset -> {
            reopenedAt.set(offset);
            return new ByteArrayInputStream(wav, Math.toIntExact(offset), wav.length - Math.toIntExact(offset));
        };
        try (StreamingPcmDecoder decoder = new StreamingPcmDecoder(
                new ByteArrayInputStream(wav), "test-8bit", wav.length, opener)) {
            assertTrue(decoder.isSeekable(), "PCM WAV stream with ranges should be seekable");
            assertTrue(decoder.audioInfo().duration().toSeconds() >= 2);
            decoder.seek(Duration.ofSeconds(1));
            assertEquals(rate, decoder.framePosition());
            long reopened = reopenedAt.get();
            // data chunk starts after the WAV header; 8-bit mono => 1 byte per frame.
            int headerBytes = wav.length - frames;
            assertEquals(headerBytes + rate, reopened,
                    "byte offset must be dataStart + targetFrames * sourceFrameSize(1)");
            byte[] buf = new byte[rate * 2];
            int n = decoder.read(buf, 0, buf.length);
            assertEquals(buf.length, n);
            assertEquals(2L * rate, decoder.framePosition());
        }
    }
}
