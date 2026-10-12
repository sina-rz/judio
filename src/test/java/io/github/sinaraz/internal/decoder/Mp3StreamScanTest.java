package io.github.sinaraz.internal.decoder;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Reads MP3 headers from hand-built bytes, so no network or fixture is needed. */
class Mp3StreamScanTest {

    @Test
    void readsFrameHeaderAndXingFrameCount() {
        byte[] data = new byte[64];
        // MPEG-1 Layer III, no CRC, 128 kbps, 44.1 kHz, no padding, joint stereo.
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xFB;
        data[2] = (byte) 0x90;
        data[3] = (byte) 0x64;
        // Xing header after the 32-byte side information of a stereo MPEG-1 frame.
        int xing = 4 + 32;
        byte[] tag = "Xing".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, data, xing, 4);
        data[xing + 7] = 0x01;   // flags: frames present
        data[xing + 10] = 0x10;  // frames = 0x1000 = 4096
        Mp3StreamScan scan = Mp3StreamScan.find(data, 0L);
        assertEquals(0L, scan.audioStart());
        assertEquals(44_100, scan.sampleRate());
        assertEquals(128_000, scan.bitrate());
        assertEquals(1152, scan.samplesPerFrame());
        assertEquals(4096L, scan.xingFrames());
    }

    @Test
    void skipsJunkBeforeTheFirstFrameAndCountsTagBytes() {
        byte[] data = new byte[32];
        data[3] = 0x42;                // junk, not a sync word
        data[4] = (byte) 0xFF;
        data[5] = (byte) 0xFB;
        data[6] = (byte) 0x90;
        data[7] = (byte) 0x64;
        Mp3StreamScan scan = Mp3StreamScan.find(data, 100L);
        assertEquals(104L, scan.audioStart());
        assertEquals(-1L, scan.xingFrames());
    }

    @Test
    void rejectsDataWithoutLayerThreeHeader() {
        byte[] data = new byte[16];
        data[0] = (byte) 0xFF;
        data[1] = (byte) 0xF5;   // layer bits 10 = Layer II, not Layer III
        data[2] = (byte) 0x90;
        assertNull(Mp3StreamScan.find(data, 0L));
    }
}
