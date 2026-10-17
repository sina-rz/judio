## [0.2.0]

### Added
- Pure-Java Ogg Opus playback via Concentus and Ogg Vorbis playback via JOrbis, with a separate
  bounded Ogg page/packet reader. Local files are indexed for granule-exact duration and seeking;
  HTTP/HTTPS Ogg streams decode incrementally with unknown duration and reset-and-replay seeking.
- Local Ogg Vorbis comments/OpusTags metadata and embedded cover-art extraction.
- End-to-end Ogg tests through `JudioPlayer`, plus local-file, HTTP, HTTPS, chaining, timing,
  truncation, metadata, CRC, and non-zero-origin coverage. The Vorbis origin test uses the
  spec-shaped two-packet first audio page, not an injected first-packet granule.
- Third-party license texts/notices and CC0 fixture provenance. A real Layer II fixture now guards
  the existing JLayer MPEG Layers I/II/III dispatch; a manual decoder audit is documented.

### Changed
- HTTP/HTTPS Ogg seeking now reopens the stream and decodes/discards from byte zero, including when
  the server does not support byte ranges; long seeks can therefore repeat substantial network I/O.
- Format support documentation now lists local and remote Ogg Opus/Vorbis and their limitations.
- Corrected the `DecoderFactory` MP3 capability documentation to include MPEG-2.5.

## [0.1.7] - 2026-10-13

### Fixed
- `addFiles` after `close()`, and other queue/transport mutations after close,
  now fail with `PlaybackStateException` instead of silently succeeding or
  leaking a raw `RejectedExecutionException`.
- Registering the same listener twice no longer delivers every event twice,
  and progress snapshots can no longer reappear after being removed.
- `PlaybackConfig` rejects infinite/NaN speeds and bounds the output buffer
  (64 MiB) and decode chunk (1 MiB) sizes; `StreamOptions` bounds its buffer
  (64 MiB) and retry count (100).
- MP3 detection and scanning now accept MPEG Layers I and II (in addition to
  III) and reject reserved/impossible frame headers instead of misreading them.
- Streamed MP3 seeking on sub-second streams no longer divides by zero;
  seeks near the start no longer fail spuriously, and a failed stream seek no
  longer destroys the working decoder.
- Streamed 8-bit and µ-law WAV seeking now converts frames to bytes with the
  source frame size (it previously used the 16-bit output size and landed at
  the wrong position); streamed WAV header/chunk parsing now reads the full
  header instead of relying on a single socket read.
- MP3 decoding now stops cleanly if a file changes sample rate or channel
  count mid-stream instead of silently producing garbage.
- The HTTP stream reader resets its retry budget after a successful reconnect
  (a stream that kept making progress could run out of retries days into a
  long listen), and closing it now interrupts the blocked fetch.
- Error messages no longer include URL credentials, query strings, or
  fragments (e.g. signed URLs); the structured exception fields still carry
  the full address.
- The audio device is released if opening the simulated or system output
  fails partway, and a failed `load` in the console player no longer clears
  the queue first.
- ID3v2.3 unsynchronisation is now undone before parsing frames, and the
  cover-art cache key includes file size and modification time so replaced
  files are not served stale art.
- The GUI Play/Pause shortcut moved to Ctrl+Space so plain Space keeps its
  normal button behavior.

### Added
- `insertFile(int, Path)` on the player, mirroring queue insertion.
- Dependency locking (`gradle.lockfile`), a CI workflow, and this changelog.
- The jar names a stable JPMS automatic module (`io.github.sinaraz.judio`)
  and bundles the MIT text and third-party notices under `META-INF`.
- Tests now generate their MP3 fixtures and serve streams from a loopback
  server, so the suite (including streaming tests) runs anywhere with no
  network or media files.

### Changed
- WAV/AIFF/AU are documented as the PCM formats they are; FLAC and Ogg files
  are rejected with an explicit unsupported-format error (as before at
  runtime, now also in the queue: `.flac`/`.ogg` files are no longer accepted
  into a queue only to fail later).

## [0.1.6] - 2026-09-03

First version published to Maven Central.

[0.1.7]: https://github.com/sina-rz/judio/releases/tag/v0.1.7
[0.1.6]: https://github.com/sina-rz/judio/releases/tag/v0.1.6
[0.2.0]: https://github.com/sina-rz/judio/releases/tag/v0.2.0
