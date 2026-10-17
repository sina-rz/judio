package io.github.sinaraz.internal.ogg;

/** One pure-Java codec adapter that converts a single compressed audio packet to canonical PCM16LE. */
interface OggPacketDecoder extends AutoCloseable {

    /** Decodes one packet to signed, little-endian, interleaved PCM. */
    byte[] decode(OggPacket packet);

    int channels();

    @Override
    void close();
}
