package io.github.sinaraz.internal.decoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FormatSnifferTest {

    private static byte[] header(int b1, int b2) {
        byte[] head = new byte[16];
        head[0] = (byte) 0xFF;
        head[1] = (byte) b1;
        head[2] = (byte) b2;
        return head;
    }

    @Test
    void allMpegLayersAreMp3() {
        // byte1: sync(111) + version + layer + crc-bit; byte2: bitrate/rate indices
        assertEquals(FormatSniffer.Format.MP3,
                FormatSniffer.detect(header(0xFF, 0x90), 16), "Layer I (layer bits 11)");
        assertEquals(FormatSniffer.Format.MP3,
                FormatSniffer.detect(header(0xFD, 0x90), 16), "Layer II (layer bits 10)");
        assertEquals(FormatSniffer.Format.MP3,
                FormatSniffer.detect(header(0xFB, 0x90), 16), "Layer III (layer bits 01)");
    }

    @Test
    void reservedOrImpossibleHeadersAreUnknown() {
        assertEquals(FormatSniffer.Format.UNKNOWN,
                FormatSniffer.detect(header(0xEB, 0x90), 16), "reserved version id (01)");
        assertEquals(FormatSniffer.Format.UNKNOWN,
                FormatSniffer.detect(header(0xFB, 0xF0), 16), "bitrate index 15 is forbidden");
        assertEquals(FormatSniffer.Format.UNKNOWN,
                FormatSniffer.detect(header(0xFB, 0x9C), 16), "sample-rate index 3 is reserved");
        assertEquals(FormatSniffer.Format.UNKNOWN,
                FormatSniffer.detect(new byte[] {(byte) 0xFF, (byte) 0xFB, (byte) 0x90}, 2),
                "too short to validate");
    }

}
