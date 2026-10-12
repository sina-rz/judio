package io.github.sinaraz.internal.decoder;

import java.nio.charset.StandardCharsets;

/**
 * Reads the first MP3 frame header and an optional Xing/Info header from the start of a stream.
 *
 * <p>The Xing or Info header, written by LAME and most encoders, holds the frame count. With it the
 * duration is exact. Without it, the duration is estimated from the bitrate of the first frame.
 *
 * @param audioStart      byte offset of the first audio frame in the file (the size of any ID3 tag,
 *                        plus padding before the sync word)
 * @param sampleRate      sample rate in hertz from the frame header
 * @param bitrate         bitrate in bits per second from the frame header
 * @param samplesPerFrame samples per channel per frame: 1152 for MPEG-1, 576 for MPEG-2 and 2.5
 * @param xingFrames      number of audio frames from the Xing/Info header, or {@code -1} if absent
 */
record Mp3StreamScan(
        long audioStart,
        int sampleRate,
        int bitrate,
        int samplesPerFrame,
        long xingFrames) {

    private static final int[] BITRATE_MPEG1_L3 = {
        0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0};
    private static final int[] BITRATE_MPEG2_L3 = {
        0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0};
    private static final int[] RATE_MPEG1 = {44100, 48000, 32000};
    private static final int[] RATE_MPEG2 = {22050, 24000, 16000};
    private static final int[] RATE_MPEG25 = {11025, 12000, 8000};

    /**
     * Finds the first valid Layer III frame in {@code data}.
     *
     * @param data     the bytes that follow any ID3 tag, starting at the tag's end
     * @param tagBytes the number of bytes of ID3 tag that came before {@code data}
     * @return the scan, or {@code null} if no valid Layer III frame header is present
     */
    static Mp3StreamScan find(byte[] data, long tagBytes) {
        for (int i = 0; i + 4 <= data.length; i++) {
            int b0 = data[i] & 0xFF;
            int b1 = data[i + 1] & 0xFF;
            int b2 = data[i + 2] & 0xFF;
            int b3 = data[i + 3] & 0xFF;
            if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) {
                continue;
            }
            int version = (b1 >> 3) & 3;   // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5, 1 = reserved
            int layer = (b1 >> 1) & 3;     // 1 = Layer III
            int bitrateIndex = (b2 >> 4) & 15;
            int rateIndex = (b2 >> 2) & 3;
            if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) {
                continue;
            }
            boolean mpeg1 = version == 3;
            int bitrate = (mpeg1 ? BITRATE_MPEG1_L3 : BITRATE_MPEG2_L3)[bitrateIndex] * 1000;
            int sampleRate = (mpeg1 ? RATE_MPEG1 : version == 2 ? RATE_MPEG2 : RATE_MPEG25)[rateIndex];
            int samplesPerFrame = mpeg1 ? 1152 : 576;
            boolean mono = ((b3 >> 6) & 3) == 3;
            boolean crc = (b1 & 1) == 0;
            int sideInfo = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
            int xing = i + 4 + (crc ? 2 : 0) + sideInfo;
            return new Mp3StreamScan(
                    tagBytes + i,
                    sampleRate,
                    bitrate,
                    samplesPerFrame,
                    readXingFrames(data, xing));
        }
        return null;
    }

    /** Returns the frame count from a Xing/Info header at {@code at}, or -1 if there is none. */
    private static long readXingFrames(byte[] data, int at) {
        if (at < 0 || at + 8 > data.length) {
            return -1L;
        }
        String tag = new String(data, at, 4, StandardCharsets.US_ASCII);
        if (!tag.equals("Xing") && !tag.equals("Info")) {
            return -1L;
        }
        long flags = readInt(data, at + 4);
        if ((flags & 1) == 0 || at + 12 > data.length) {
            return -1L;
        }
        long frames = readInt(data, at + 8);
        return frames > 0 ? frames : -1L;
    }

    private static long readInt(byte[] data, int at) {
        return ((data[at] & 0xFFL) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
