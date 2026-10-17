# Decoder test audio

All generated audio content in this directory is synthetic and dedicated to the public domain under CC0 1.0 Universal. The full CC0 text is included in [`CC0-1.0.txt`](CC0-1.0.txt); see also <https://creativecommons.org/publicdomain/zero/1.0/>. FFmpeg is used only to create fixtures; Gradle tests read the committed files and do not invoke FFmpeg.

## MPEG Layer II fixture

`mp2-layer2-44100-stereo.mp2` is a 2.4-second, 523 Hz synthetic tone encoded by FFmpeg 7.1.5's built-in MP2 encoder. It contains 92 MPEG Layer II frames, 44.1 kHz stereo, and decodes to 105,984 frames per channel (2.403265306 seconds, including whole-frame encoder padding). The fixture is used to verify the existing JLayer path with a real Layer II bitstream, non-silent PCM, and seeking.

Reproduce it from the repository root with FFmpeg built with its MP2 encoder:

```sh
ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'sine=frequency=523:sample_rate=44100:duration=2.4' \
  -map_metadata -1 -ac 2 -c:a mp2 -b:a 192k -y \
  src/test/resources/audio/mp2-layer2-44100-stereo.mp2
```

The Ogg Opus/Vorbis fixture details and commands are in [`ogg/README.md`](ogg/README.md).

`ogg/test-server.p12` is not audio: it is a self-signed localhost PKCS#12 keypair used only by the HTTPS loopback test. Its test password is stored in the test source. It is never loaded by production code and must not be used as a real certificate.
