package io.github.sinaraz.internal.processing;

/**
 * Conversion between raw PCM bytes and normalised {@code float} samples in {@code [-1, 1)}.
 *
 * <p>Samples are handled one at a time using their declared width, signedness and byte order.
 * Nothing here assumes 16-bit input; the generic methods support 8, 16, 24 and 32 bits.
 * Writing back to a narrower format saturates instead of wrapping.
 */
public final class PcmCodec {


    /**
     * Decodes {@code len} bytes of PCM starting at {@code src[off]} into {@code dst}.
     *
     * @param bitsPerSample 8, 16, 24 or 32
     * @param bigEndian     byte order of multi-byte samples
     * @param signed        {@code false} for unsigned PCM (8-bit WAV is the usual example)
     * @return number of samples written to {@code dst}
     */
    public static int toFloat(byte[] src, int off, int len, int bitsPerSample, boolean bigEndian,
                              boolean signed, float[] dst, int dstOff) {
        checkBits(bitsPerSample);
        int bytes = bitsPerSample / 8;
        if (len % bytes != 0) {
            throw new IllegalArgumentException("length " + len + " is not a multiple of " + bytes);
        }
        int count = len / bytes;
        if (bitsPerSample == 16 && !bigEndian && signed) {
            // Hot path: the canonical PCM16 little-endian format used by the whole pipeline.
            for (int s = 0, p = off; s < count; s++, p += 2) {
                short value = (short) ((src[p + 1] << 8) | (src[p] & 0xFF));
                dst[dstOff + s] = value * (1.0f / 32768.0f);
            }
            return count;
        }
        double scale = 1.0 / (1L << (bitsPerSample - 1));
        for (int s = 0; s < count; s++) {
            int p = off + s * bytes;
            int raw = 0;
            if (bigEndian) {
                for (int k = 0; k < bytes; k++) {
                    raw = (raw << 8) | (src[p + k] & 0xFF);
                }
            } else {
                for (int k = bytes - 1; k >= 0; k--) {
                    raw = (raw << 8) | (src[p + k] & 0xFF);
                }
            }
            long value;
            if (signed) {
                int shift = 32 - bitsPerSample;
                value = ((long) (raw << shift)) >> shift;
            } else {
                value = (raw & mask(bitsPerSample)) - (1L << (bitsPerSample - 1));
            }
            dst[dstOff + s] = (float) (value * scale);
        }
        return count;
    }

    /**
     * Encodes {@code count} float samples into PCM bytes. Values outside {@code [-1, 1]} are
     * saturated to the representable range.
     *
     * @return number of bytes written
     */
    public static int fromFloat(float[] src, int count, int bitsPerSample, boolean bigEndian,
                                boolean signed, byte[] dst, int dstOff) {
        checkBits(bitsPerSample);
        int bytes = bitsPerSample / 8;
        if (bitsPerSample == 16 && !bigEndian && signed) {
            // Hot path: same rounding and clamping as the generic loop below, without boxing to double.
            for (int s = 0, p = dstOff; s < count; s++, p += 2) {
                float x = src[s];
                int value;
                if (x >= 1.0f) {
                    value = 32767;
                } else if (x <= -1.0f) {
                    value = -32768;
                } else {
                    // floor(v + 0.5) is Math.round(v) here; the float sum is exact for |v| <= 32768.
                    value = Math.min(32767, (int) Math.floor(x * 32768.0f + 0.5f));
                }
                dst[p] = (byte) value;
                dst[p + 1] = (byte) (value >> 8);
            }
            return count * 2;
        }
        long maxValue = (1L << (bitsPerSample - 1)) - 1;
        long minValue = -(1L << (bitsPerSample - 1));
        double scale = (double) (1L << (bitsPerSample - 1));
        for (int s = 0; s < count; s++) {
            double x = Math.max(-1.0, Math.min(1.0, (double) src[s]));
            long value = Math.round(x * scale);
            if (value > maxValue) {
                value = maxValue;
            } else if (value < minValue) {
                value = minValue;
            }
            if (!signed) {
                value += 1L << (bitsPerSample - 1);
            }
            int p = dstOff + s * bytes;
            for (int k = 0; k < bytes; k++) {
                int shiftBits = bigEndian ? (bytes - 1 - k) * 8 : k * 8;
                dst[p + k] = (byte) (value >>> shiftBits);
            }
        }
        return count * bytes;
    }

    /** Fast path: signed 16-bit little-endian to float. */
    public static int toFloat16LE(byte[] src, int off, int len, float[] dst, int dstOff) {
        return toFloat(src, off, len, 16, false, true, dst, dstOff);
    }

    /** Fast path: float to signed 16-bit little-endian with saturation. */
    public static int fromFloat16LE(float[] src, int count, byte[] dst, int dstOff) {
        return fromFloat(src, count, 16, false, true, dst, dstOff);
    }

    private static void checkBits(int bits) {
        if (bits != 8 && bits != 16 && bits != 24 && bits != 32) {
            throw new IllegalArgumentException("unsupported sample size: " + bits);
        }
    }

    private static long mask(int bits) {
        return (1L << bits) - 1;
    }
}
