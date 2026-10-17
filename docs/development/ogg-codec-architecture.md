# Ogg Opus and Vorbis architecture decision

**Status:** Accepted and implemented  
**Decision date:** 2026-10-15  
**Scope:** Internal decoders and metadata; no public playback API change.

## Context and repository findings

The baseline was `master` at `b3f1c4e`. Judio already had a content-sniffing `DecoderFactory`, a pull-based `AudioDecoder` contract, and a mono/stereo signed PCM16LE pipeline. The engine reads PCM on its decode stage and sends it through the same format-independent speed, pan, volume, and output stages used by MP3 and PCM files. The Ogg branch originally recognized the container signature but did not decode it.

The task requires pure-Java/JVM-native playback, no native runtime or subprocess, and preservation of MP3/WAV/AIFF/AU behavior. Container demuxing and compressed-audio decoding are separate responsibilities; codec-specific packet logic must stay behind the existing decoder boundary.

## Research and alternatives

Symphonia's format/codec architecture was consulted only as a design reference: its guide describes format readers producing packets for codec decoders, and its v0.6.0 codec table lists Ogg and Vorbis but no first-party Opus decoder. Its third-party Opus adapter uses native `libopus`. No Symphonia source or translated implementation is used here.

The authoritative behavior references are RFC 3533 (Ogg page framing, lacing, serial/sequence numbers, CRC), RFC 7845 (Ogg Opus headers, 48 kHz granules, pre-skip, EOS trimming, and channel mapping), RFC 6716 (Opus packets), and the Vorbis I specification (three headers, per-channel PCM granules, and special first-page positioning/trimming rules).

| Approach | Assessment |
|---|---|
| Maintained Ogg reader plus separate codec libraries | Best split in principle, but no Java reader was found that both offers the needed low-level packet/page contract and avoids introducing unverified buffering, CRC, continuation, and granule behavior. |
| One Java library for Ogg and Vorbis | `com.github.stephengold:j-ogg-vorbis:1.0.6` is pure Java and includes Ogg/Vorbis, but it does not decode Opus. Its packet API does not take page granules; an earlier experiment required manual end trimming and exposed permissive bounds handling. Its license is an informal attribution grant rather than a standard SPDX license. |
| One small internal Ogg reader plus separate packet decoders | **Selected.** A single codec-independent demux layer serves both formats and owns page integrity, packet reconstruction, resource limits, and EOF behavior. Mature pure-Java codec implementations handle the compressed packets. |
| Native wrappers, FFmpeg, or subprocesses | Rejected: these violate the JVM-native runtime requirement. |

## Selected components, licenses, and runtime properties

- **Ogg framing:** original Judio code in `internal.ogg.OggPacketReader`. It follows the public Ogg framing specification; it is not copied from Symphonia or another demuxer. It validates capture/version/flags, CRC, page sequence, continuation state, packet lacing, and EOF. It bounds packets to 16 MiB, in-progress packet buffering to 32 MiB, and simultaneous logical streams to 64. Codec headers have an additional 8 MiB bound.
- **Opus:** `io.github.jaredmdobson:concentus:1.0.2`. Maven Central describes it as a portable pure-Java Opus encoder/decoder/multistream implementation and explicitly says it does not parse Ogg. The artifact has no mandatory native or runtime dependency. Its upstream BSD-style Opus license and full conditions/disclaimer are recorded in `LICENSE-CONCENTUS.txt`.
- **Vorbis:** `org.jcraft:jorbis:0.0.17`. The pure-Java artifact exposes packet-level JOrbis decode state and includes the `com.jcraft.jogg` packet classes. Its Maven metadata lists LGPL; the upstream source repository includes `COPYING.LIB` (LGPL v2.1). The LGPL text is included as `LICENSE-LGPL-2.1.txt`. Judio calls the codec API but keeps Ogg page parsing in its own layer.
- **Distribution:** JLayer, Concentus, and JOrbis are ordinary separate Maven runtime dependencies. Judio does not shade, modify, or bundle their classes. The published Judio jar carries its MIT text, the applicable third-party license texts, and `NOTICE.txt`.
- **Test media:** synthetic sine-wave Ogg fixtures and the MPEG Layer II fixture are dedicated to CC0 1.0; their generation commands and exact provenance are in `src/test/resources/audio/ogg/README.md` and `src/test/resources/audio/README.md`. FFmpeg/libopus/libvorbis are used only to generate or independently inspect test assets, never at runtime. The PKCS#12 test certificate is test-only and is used only by a loopback HTTPS test.

## MP3 decoder audit

Judio's existing MP3 dependency is `javazoom:jlayer:1.0.1`. Its Maven POM lists LGPL and only a test-scoped JUnit dependency; the decoder is pure Java. The published JLayer 1.0.1 source/documentation describes MPEG-1/2/2.5 Layers I/II/III support. Independently, inspecting the exact resolved jar with `javap -c -p javazoom.jl.decoder.Decoder` shows its layer dispatch selecting `LayerIDecoder`, `LayerIIDecoder`, and `LayerIIIDecoder` for layer values 1, 2, and 3. Judio's `Mp3Decoder.samplesPerFrame` uses the corresponding MPEG sample counts (384 for Layer I, 1152 for Layer II, and 1152/576 for Layer III depending on MPEG version). `FormatSniffer` recognizes non-reserved MPEG audio layer/version headers; the decoder then validates/decodes frames.

The existing generated silent fixture proves the local Layer III framing/duration/seeking path but cannot prove meaningful decoded audio. To close part of that evidence gap, the test suite now includes a committed, non-silent FFmpeg 7.1.5 Layer II fixture. `Mp3DecoderTest.realMpegLayerTwoFixtureDecodesAndSeeksAccurately` verifies 44.1 kHz stereo, 105,984 output frames, non-silent PCM, and PCM equality after a one-second seek. The same file was benchmarked locally at 210x realtime decode-only and 231x with the playback pipeline in the latest run. These short-fixture measurements are environment-specific. No Layer I fixture is committed, so Layer I is verified from JLayer's dispatch/code and documentation, not claimed as independently exercised end-to-end. Existing MP3 code was not replaced or routed through Ogg.

## Integration design

`DecoderFactory` still selects by file/stream contents, not extension. `FormatSniffer` identifies only the Ogg container. `OggHeaderParser` then recognizes `OpusHead` or `\x01vorbis`; unknown valid Ogg codecs are reported as unsupported, while malformed pages, codec headers, packets, granules, and physical truncation are reported as corruption.

The internal boundary is:

```text
OggPacketReader -> OggStreamDecoder -> (OpusPacketDecoder | VorbisPacketDecoder) -> PCM16LE
```

The packet decoders hold one codec state per logical stream. They do not handle container pages. The shared stream adapter applies page-level granule trims and emits the existing canonical signed 16-bit little-endian PCM. Judio's later playback pipeline always outputs stereo, including mono input, as before.

### Local files

`OggFileProbe` validates and scans the local file once without decoding audio. It identifies the supported logical audio stream(s), checks same-format chaining, computes playable frame count from packet durations and page granules, and reads Vorbis/Opus comments. Playback then reopens and decodes packet-by-packet. Seeking resets codec/container state and decodes/discards from the beginning to the target. This is exact for tested files but can be slow for a long file and late seek; a page/preroll seek index is not implemented.

For same-rate/same-channel chained streams, each chain gets fresh codec and granule state and the durations are summed. A chain that changes canonical sample rate or channel count is rejected explicitly because `AudioInfo` and the playback output format are fixed for a track. The first supported audio logical stream is selected; unrelated multiplexed streams are validated at the Ogg layer and ignored.

### HTTP and HTTPS streams

`DecoderFactory` passes the existing `HttpByteStream` read-ahead source to the Ogg decoder and supplies an offset-aware opener when the URL supports byte ranges. Ogg is consumed incrementally; the complete remote file is not downloaded or indexed first. Remote Ogg reports unknown duration/frame count (`Duration.ZERO`/0). A seek bootstraps the selected stream's codec headers, probes checksum-verified pages with HTTP ranges, chooses a page at or before the granule target minus codec preroll, and opens a new `Range: bytes=N-` request at that page boundary. Remote range seeking currently assumes a single monotonic granule timeline; global-time indexing across chained segments is not implemented. Opus uses at least 3,840 samples (80 ms) of preroll; Vorbis uses a conservative 16,384-frame preroll to cover its usual two prior content packets. Targets within the first audio page start at that page rather than byte zero. Servers that do not advertise byte-range support expose remote Ogg as non-seekable; no replay-from-zero fallback is used. Remote comment tags are not attached to `Track` metadata, consistent with Judio's existing remote-track metadata policy. The actual HTTP and HTTPS routes are covered with loopback servers through `MediaLocation` and `DecoderFactory`, including assertions that seeks issue nonzero Range requests and return PCM matching sequential decode.

### Comments and cover art

Local `MetadataService` routes Ogg files to `OggMetadataReader`. It reads Vorbis comments/OpusTags for title, artist, album, album artist, genre, date/year, track, and disc number. Cover presence and embedded art are read from `METADATA_BLOCK_PICTURE` and `COVERART`/`COVERARTMIME`; malformed optional art is ignored, and image extraction is capped at 8 MiB. Comments do not affect the playback decoder's PCM interface.

## Granule/timing decisions

Granules are handled on completed pages, not invented per-packet. No synthetic first-audio-packet granule position is inserted.

- **Opus:** packet durations are measured at 48 kHz. Exactly the declared pre-skip samples are decoded then discarded. The first audio-page granule may be greater than the samples decoded on that page (a non-zero initial sample position); it must not be smaller unless that page is EOS. EOS trimming is relative to the previous completed audio-page granule, or zero if there was none. Tests shift every positive granule on mono/stereo fixtures and check that the playable sample count remains stable.
- **Vorbis:** granules are per-channel PCM sample positions. The first audio-page granule may express a non-zero initial position or start trim only under the Vorbis first-page rule: exactly two audio packets complete on that first audio page and the third begins on a fresh page. The valid non-zero-origin tests rebuild the pages to satisfy that rule, recompute CRCs, and verify the resulting decoded sample count. A shifted multi-packet first page is explicitly rejected; it is not accepted as a synthetic workaround. EOS granules trim the final decoded samples.
- **Truncation:** a physically partial page or unfinished continued packet at EOF is corruption. A stream ending cleanly at a page boundary without EOS (common for live/partial transfers) remains playable for complete pages but has unknown duration/frame count. The same policy applies to local and remote decoding.

## Verified coverage and limitations

A full Java 25 validation build on a temporary copy completed successfully: 224 tests, 0 failures/errors, and 2 skipped (the opt-in external MP3 test and the display-dependent Swing window test). The copy omitted only the Foojay toolchain-resolver and Maven Central publishing plugins because those plugin artifacts could not be resolved in the sandbox; source compilation, all test/bench/sample compilation, packaging, and checks ran.

The committed tests cover:

- Ogg page CRC, lacing, packet continuation, sequence numbers, serial reuse, interleaved logical streams, malformed flags, bad checksums, oversized packets, and truncated physical pages.
- Mono and stereo Opus/Vorbis fixtures, exact local PCM frame counts, non-silent output, local seek equivalence, filename-independent detection, comments, cover art, local and remote decoder cleanup, and explicit unsupported-codec/chain errors.
- Same-format Opus and Vorbis chains, incompatible-format chains, page-boundary missing-EOS streams, Opus non-zero origin, spec-shaped Vorbis non-zero origin, and rejection of an invalid multi-packet Vorbis initial-origin mutation.
- All four mono/stereo codec fixtures through `JudioPlayer`, the decode stage, PCM pipeline, and a recording output.
- All four fixtures over both HTTP and HTTPS `MediaLocation` routes, including unknown duration, nonzero Range-request assertions, and seeked-PCM equivalence checks.

Deliberate limits: only mono/stereo output is supported (Ogg Opus mapping family 0 and family 1 with one/two output channels; other channel layouts and mapping families are rejected explicitly). Ogg-FLAC, Speex, Theora, and unknown Ogg streams are not decoded. Remote duration and remote tags are unavailable. Local Ogg seeking still uses reset-and-discard. Remote Ogg seeking requires the server to advertise byte ranges and a known content length; without ranges the stream remains playable but non-seekable.

## Local benchmark snapshot

Command: `sh ./gradlew benchmark -PbenchFiles=src/test/resources/audio/ogg/vorbis-mono.ogg,src/test/resources/audio/ogg/opus-mono.ogg,src/test/resources/audio/mp2-layer2-44100-stereo.mp2`. Each input is about 2.4 seconds. Local random seek measures reset-and-decode from the beginning; decode throughput is the best of three passes. Values below are one sandbox run on 2026-10-15, not performance guarantees:

| Fixture | Open | 25 random seeks (median / p90 / max) | Decode-only | Decode + pipeline |
|---|---:|---:|---:|---:|
| Vorbis mono, 44.1 kHz | 37.4 ms | 4.60 / 5.45 / 11.28 ms | 512x realtime | 242x realtime |
| Opus mono, 48 kHz | 2.2 ms | 9.18 / 12.23 / 15.71 ms | 214x realtime | 193x realtime |
| MPEG Layer II, 44.1 kHz stereo | 15.8 ms | 0.54 / 0.66 / 0.79 ms | 210x realtime | 231x realtime |

The remote HTTP/HTTPS tests validate nonzero byte-range use and PCM-equivalent seeks; they do not benchmark network latency or large-file page-probe cost.

## References

- [RFC 3533: The Ogg Encapsulation Format](https://www.rfc-editor.org/rfc/rfc3533.html)
- [RFC 7845: Ogg Encapsulation for the Opus Audio Codec](https://www.rfc-editor.org/rfc/rfc7845.html)
- [RFC 6716: Definition of the Opus Audio Codec](https://www.rfc-editor.org/rfc/rfc6716.html)
- [Vorbis I specification](https://xiph.org/vorbis/doc/Vorbis_I_spec.html)
- [Symphonia README](https://github.com/pdeljanov/Symphonia/blob/main/symphonia/README.md) and [getting-started guide](https://github.com/pdeljanov/Symphonia/blob/main/GETTING_STARTED.md) (architecture reference only)
- [Concentus source and license](https://github.com/lostromb/concentus) and [Maven Central metadata](https://central.sonatype.com/artifact/io.github.jaredmdobson/concentus/1.0.2)
- [JOrbis source and COPYING.LIB](https://github.com/ymnk/jorbis) and [Maven Central metadata](https://central.sonatype.com/artifact/org.jcraft/jorbis)
- [JLayer 1.0.1 Maven POM](https://repo1.maven.org/maven2/javazoom/jlayer/1.0.1/jlayer-1.0.1.pom) and [upstream source mirror](https://github.com/wkpark/JLayer)
- [j-ogg-all / j-ogg-vorbis](https://github.com/stephengold/j-ogg-all)
