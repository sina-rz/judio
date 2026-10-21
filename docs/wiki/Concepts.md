# Core concepts

This page explains the ideas behind judio. Read it before the [tutorial](Tutorial.md), or come back
to it when a term confuses you.

## What judio is

judio is a Java library that plays audio files. It is written in pure Java, so it runs anywhere
JDK 25 runs. It needs no native libraries, no JavaFX, and no media framework.

You use one class, `JudioPlayer`, for almost everything. You give it files, you tell it what to
do, and it reports back through events.

## The player

`JudioPlayer` is the **facade**: the single public entry point. Create one with:

- `JudioPlayer.create()` for the default sound device and default settings.
- `JudioPlayer.builder()` when you want to change the configuration, the audio output, or the
  decoders. Call `build()` at the end.

A player holds resources such as an audio device and background threads. It implements
`AutoCloseable`, so use it in a `try` block, or call `close()` yourself.

## The queue

The **queue** is the ordered list of tracks the player will play. Each entry is a `Track`, which
holds where the audio comes from (a local file or a stream address) and the metadata that was read
from the file.

- `addFiles(...)`, `addDirectory(...)`, and `addUrl(...)` add tracks to the end.
- `remove(index)` and `move(from, to)` edit the list.
- `queue()` returns a copy of the list, in the order the user sees it.
- `queueSnapshot()` returns the current position, the size, the repeat mode, and the shuffle flag.

**Repeat** decides what happens at the end of the queue:

| Mode | At the end of a track | At the end of the queue |
|---|---|---|
| `OFF` | Play the next track | Stop |
| `ONE` | Replay the same track | Replay the same track (the queue never advances) |
| `ALL` | Play the next track | Start again from the first track |

**Shuffle** changes the play order but not the visible queue. Pass a seeded `Random` to the builder
to get the same order each run.

## Transport and controls

The **transport** is the set of commands that move through playback: `play()`, `pause()`,
`resume()`, `stop()`, `next()`, `previous()`, `seek(...)`, and `seekRelative(...)`.

The **controls** change how the sound is shaped: `setVolume(...)`, `setMuted(...)`,
`setPlaybackSpeed(...)`, `setPreservePitch(...)`, and `setPan(...)`.

Values that are out of range are clamped to the nearest allowed value. By default, speed changes the
pitch as well, as a tape does when it is played faster or slower. Turn on `setPreservePitch(true)`
to change the tempo and keep the pitch instead (see [Speed and pitch](#speed-and-pitch)).

## Speed and pitch

judio offers two ways to change speed, and they sound different:

- **Resampling** (the default) reads the audio faster or slower. It is cheap, and the pitch moves
  with the speed.
- **Time-stretching** (`setPreservePitch(true)`) changes the tempo and keeps the pitch. judio uses
  WSOLA, a waveform-similarity overlap-add method. It costs more CPU, and it adds about 35 ms of
  delay, which is why the position shown to you is corrected for that delay.

Both methods work for every supported format. The change takes effect on the next block of sound,
so you can switch while a track plays.

## State

The player is always in one **state**, described by the `PlaybackState` enum: `EMPTY`, `LOADING`,
`READY`, `PLAYING`, `PAUSED`, `SEEKING`, `STOPPING`, `STOPPED`, `ENDED`, `ERROR`, or `CLOSED`.
The engine checks every transition against a fixed table, so the player cannot jump between
states that make no sense.

Some commands are rejected in the wrong state. For example, `pause()` throws a
`PlaybackStateException` when nothing is playing. Read the state first if you are not sure.

## Snapshots

A **snapshot** is an immutable value that describes the player at one moment: the state, the
position, the duration, the volume, the speed, the queue position, and the current track.

`snapshot()` returns one. Because a snapshot never changes, it is safe to pass to another thread
and to draw from. Read it when you need to update a screen, and do not poll it in a tight loop.

## Events

A **listener** implements `PlaybackListener`, which has one default method per event:

- `onStateChanged`: the state changed, for example from playing to paused.
- `onTrackChanged`: the current track changed.
- `onTrackLoaded`: a track's file opened and its audio format is known.
- `onTrackMetadataChanged`: asynchronous tags or metadata status for one queued track changed.
- `onProgress`: the position moved. These updates are periodic, and they may be skipped when the
  listener is slow.
- `onQueueChanged`: the queue contents, order, or position changed. Consecutive queue snapshots may be
  coalesced when the event thread falls behind; the latest consistent view is delivered.
- `onPlaybackCompleted`: a track played to its end.
- `onError`: something failed, such as a corrupt file or a lost device.

Add a listener with `addListener(...)`. Registering the same listener instance twice has no
extra effect, and `removeListener(...)` stops its deliveries. Listeners run on one dedicated
event thread. Non-coalesced state, track, completion, and error events retain publication order;
progress updates and consecutive queue snapshots may be coalesced when the listener falls behind.
They never run on the audio thread, so a slow listener cannot cause a click or a stutter. An exception thrown
by a listener is logged, and playback continues.

## Threads

judio uses three kinds of thread:

- The **control** thread is yours. It calls the player's methods.
- The **audio** thread reads decoded sound and writes it to the device.
- The **event** thread runs your listeners.

Keep listener callbacks short. Calling `close()` from a listener is safe—it never waits for its own
event thread—but it may wait for playback/decode workers up to the configured shutdown deadline. If
you want to keep event delivery moving, close from an application/control thread instead.

## Decoders and formats

A **decoder** turns a file or a stream into PCM sound. The `DecoderProvider` interface chooses a
decoder for each source. `DecoderFactory.standard()` is the default. It detects each format from the
content, not its extension.

| Format | Supported |
|---|---|
| MP3 (MPEG-1/2/2.5, Layers I/II/III) | Yes |
| WAV, AIFF, AU | Yes |
| Ogg Vorbis, Opus | Yes, pure Java; mono/stereo |
| FLAC and other Ogg codecs | No. They are reported as `UNSUPPORTED_FORMAT`. |

Local Ogg files are scanned for page granules to report exact playable duration. The scan also caches
codec headers and builds a sparse, packet-aligned seek index (about one entry per second). Seeking
jumps to a nearby indexed page and decodes only codec preroll and the remaining frames, rather than
replaying the whole file. Same-rate, same-channel chained Ogg streams are supported, including seeks
across chain boundaries; a chain that changes sample rate or channel count is rejected.

HTTP/HTTPS Ogg playback stays incremental. For a finite, single-logical-stream file with a known byte
length and advertised HTTP range support, one optional tail probe reads at most 256 KiB to find a
checksum-verified terminal EOS page. Duration and frame count come from its granule, adjusted for
Opus pre-skip and any non-zero initial granule origin. Very short files can reuse an already decoded
terminal EOS without any probe (or range support), if the known byte length confirms the end. The
HTTP read-ahead buffer may fetch additional bytes as usual. A missing/invalid EOS, chaining detected
in the tail, or a failed probe leaves
`Duration.ZERO` and zero frames; metadata failure alone does not prevent playback.

Remote range seeks reuse cached codec headers and recently used page anchors. Both local and remote
seeks decode preroll before returning the requested frame (240 ms for Opus, 16,384 frames for Vorbis).
Opus prediction state after a cold seek can differ slightly from a full sequential decode, even at
the same sample position. Seeking to a known end requires no extra decoding or network requests.
When a reliable single-stream endpoint is unavailable, remote seeks safely replay from the start
instead of treating chained granules as one global timeline. Without range support or a known byte
length, remote Ogg is non-seekable. Remote Ogg comment tags are not attached to the track.

The [README](../../README.md) has the full table, including the limits of each decoder.

## Streams

A **stream** is audio that arrives over HTTP or HTTPS, such as an internet radio station or a
podcast file. Add one with `addUrl(URI)`. judio fetches the audio while it plays, so playback starts
without downloading the whole file first.

Streams differ from files in three ways:

- **Seeking depends on the decoder.** MP3 and WAV streams can seek when the server
  reports the file size and answers HTTP range requests. judio then reconnects at the matching byte
  position. Ogg Vorbis and Opus use byte-range page probes, cached headers and codec preroll when a
  reliable single-stream endpoint is available; otherwise they replay to the requested time. Ogg
  still requires a known byte length and advertised range support. Call `JudioPlayer.seek(Duration)`
  or `seekRelative(Duration)`. Unsupported sources throw `SeekNotSupportedException`.
- **Duration may be unknown.** An MP3 with a Xing or Info header reports its exact length. Other MP3
  streams estimate it from the bitrate and file size. A WAV stream reports its length when the header
  states a frame count. A finite, range-enabled single-stream Ogg file can report granule-based
  duration from a bounded tail probe. Live streams and unavailable end metadata retain
  `Duration.ZERO` and zero frames.
- **Remote tags are not loaded.** This includes Ogg Vorbis comments and OpusTags. Remote tracks have
  `Track.isRemote() == true` and metadata status `NOT_APPLICABLE`.

A background thread keeps a read-ahead buffer full. If the connection drops, judio reconnects with an
HTTP range request and continues where it stopped, when the server supports ranges. When the network
stalls past the read timeout, or the server refuses the request, a `RemoteSourceException` reports a
redacted address and the HTTP status. Error messages and `RemoteSourceException.uri()` omit URL
user-info, query strings, and fragments; a cause is sanitized if its diagnostics contain those secrets.
Keep the original URI in application state if it is needed for retry. Treat stream addresses as untrusted input:
judio connects to
whatever host an address names (including private or loopback addresses) and follows redirects,
so an application that takes addresses from users should validate them first (see the
`addUrl` Javadoc). `StreamOptions` sets the timeouts, the buffer size, the retry count, and
any request headers.

## Audio output

An **audio output** is where the sound goes. The `AudioOutput` interface is the only thing the
engine knows about the device. This has two benefits:

- `SystemAudioOutput` sends sound to the computer's sound device. It is the default.
- `SimulatedAudioOutput` keeps real-time timing but makes no sound. Tests, servers, and machines
  without a sound card use it.

You can write your own `AudioOutput`, for example to send sound over a network.

## Configuration

`PlaybackConfig` holds the tuning values: buffer sizes, the progress interval, the speed range, the
threshold for "previous restarts the track", whether a failing track is skipped, and the close
timeout. Build one with `PlaybackConfig.builder()`. Values that are out of range are rejected when
you call `build()`.

## Errors

Errors are unchecked exceptions, all based on `AudioPlayerException`:

- `QueueException`: a queue operation failed, such as adding a missing file.
- `PlaybackStateException`: the command does not apply in the current state.
- `SeekNotSupportedException`: the current source cannot be repositioned.
- `UnsupportedAudioFormatException`: the file's format is not supported.
- `DecoderException` and `CorruptFileException`: the file could not be read.
- `AudioDeviceException`: the audio device could not be opened or written.

Playback errors also reach your listener as a `PlaybackError`, which says what failed and which
track was involved.

## Layers

judio is split into layers. Each layer depends only on the layers below it.

```
JudioPlayer, PlaybackConfig, PlaybackListener   (public facade)
model, error, audio                              (public types and SPIs)
internal.engine, internal.queue                  (state machine, queue)
internal.decoder, internal.net,
internal.processing, internal.metadata           (decoding, streams, DSP, tags)
```

Only the top layer and the `model`, `error`, and `audio` packages are public. The `internal`
packages can change between releases. The `internal.net` package holds the HTTP stream reader.
