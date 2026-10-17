# Ogg decoder test fixtures

These four short audio fixtures are synthetic 440 Hz / 660 Hz sine waves generated locally with FFmpeg 7.1.5 (`lavfi`'s `sine` source), encoded as Vorbis or Opus. No third-party recording or copyrighted source audio is used. The generated audio content is dedicated to the public domain under **CC0 1.0 Universal**. The full CC0 text is included in [`../CC0-1.0.txt`](../CC0-1.0.txt); see also <https://creativecommons.org/publicdomain/zero/1.0/>.

FFmpeg, libvorbis, and libopus were used only to create independent test/reference bitstreams. They are **not** runtime dependencies; Gradle tests consume the committed files and do not invoke FFmpeg.

## Reproduction commands

Run from the repository root with an FFmpeg build that includes `libvorbis` and `libopus`:

```sh
ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'sine=frequency=440:sample_rate=44100:duration=2.4' -map_metadata -1 -ac 1 \
  -c:a libvorbis -q:a 4 -metadata title='Judio test Vorbis mono' \
  -metadata artist='Judio project' -metadata album='Generated codec fixture' \
  -metadata track='7' -y vorbis-mono.ogg

ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'sine=frequency=660:sample_rate=44100:duration=2.4' -map_metadata -1 -ac 2 \
  -c:a libvorbis -q:a 4 -metadata title='Judio test Vorbis stereo' \
  -metadata artist='Judio project' -metadata album='Generated codec fixture' \
  -metadata track='8' -y vorbis-stereo.ogg

ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'sine=frequency=440:sample_rate=48000:duration=2.4' -map_metadata -1 -ac 1 \
  -c:a libopus -application audio -b:a 64k -metadata title='Judio test Opus mono' \
  -metadata artist='Judio project' -metadata album='Generated codec fixture' \
  -metadata track='9' -y opus-mono.ogg

ffmpeg -hide_banner -loglevel error -f lavfi \
  -i 'sine=frequency=660:sample_rate=48000:duration=2.4' -map_metadata -1 -ac 2 \
  -c:a libopus -application audio -b:a 96k -metadata title='Judio test Opus stereo' \
  -metadata artist='Judio project' -metadata album='Generated codec fixture' \
  -metadata track='10' -y opus-stereo.ogg
```

## Reference properties

| File | Codec | PCM rate/channels | Expected playable frames per channel | Comment tags |
|---|---|---:|---:|---|
| `vorbis-mono.ogg` | Vorbis | 44,100 Hz / 1 | 105,840 | title, artist, album, track 7 |
| `vorbis-stereo.ogg` | Vorbis | 44,100 Hz / 2 | 105,840 | title, artist, album, track 8 |
| `opus-mono.ogg` | Opus | 48,000 Hz / 1 | 115,200 | title, artist, album, track 9 |
| `opus-stereo.ogg` | Opus | 48,000 Hz / 2 | 115,200 | title, artist, album, track 10 |

The Opus headers use a 312-sample pre-skip; the EOS granule position is 115,512, so the expected playable length is 115,512 − 312 = 115,200 frames/channel. FFmpeg 7.1.5/libopus and `ffmpeg -f s16le` output were used as independent references for the encoded stream and sample count.

The tests also create derived page streams in memory: Opus page granules are shifted to test a non-zero initial position; Vorbis packets are repaged so exactly the first two audio packets finish on the first audio page, then a positive initial origin is added and every page CRC is recomputed. A separate negative case shifts a multi-packet Vorbis first audio page and verifies it is rejected rather than treating an arbitrary synthetic packet granule as valid. Chained and missing-EOS streams are likewise derived in memory; no additional media files are needed.
