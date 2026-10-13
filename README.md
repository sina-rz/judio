# Judio

**Judio** is an audio playback library for JVM.
It is written in pure Java, so it needs no extra software
installed on your computer.


[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/JDK-25-orange.svg)](#requirements)

<img alt="judio&#39;s Swing player while a track plays" src="docs/screenshots/gui1.png" width="800"/>

## Download 
Judio is available on Maven Central

![](https://img.shields.io/maven-metadata/v.svg?metadataUrl=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fsina-rz%2Fjudio%2Fmaven-metadata.xml&label=maven-central)

```
groupId: io.github.sina-rz
artifactId: judio
```

## Try it

You need **JDK 25**. The Gradle wrapper is included, so you do not need to install Gradle.

```bash
./gradlew runGui          # a graphical player
./gradlew run             # a player in the terminal
```

No sound card? Add `--null-audio` to play without sound. The timing is real, but nothing is heard:

```bash
./gradlew run --args="--null-audio song.mp3"
```

## What you can do

- Play MP3, WAV, AIFF, and AU files
- Play internet streams from an `http://` or `https://` address
- Make a queue, with repeat and shuffle
- Play, pause, resume, stop, seek, skip, and go back
- Set the volume, mute, pan, and speed
- Change the speed without changing the pitch
- Read song titles and artists, and show cover art
- Get events when the song changes, progresses, or fails

## Supported formats

| Format | Status |
|---|---|
| MP3 | Supported |
| WAV, AIFF, AU | Supported (uncompressed sound) |
| Internet streams (MP3, WAV, AIFF, AU) | Supported. MP3 and WAV can seek if the server supports it |
| FLAC, Ogg Vorbis, Opus | Not supported yet |

judio checks what is inside a file, not its name. Details are in the
[core concepts page](docs/wiki/Concepts.md#decoders-and-formats).

## Build your own player

Start with the **[tutorial](docs/wiki/Tutorial.md)**.
Read the **[core concepts](docs/wiki/Concepts.md)** first if you want to understand how the
pieces fit together.

## For contributors

```bash
./gradlew test            # run the tests
./gradlew benchmark       # measure performance
```

The source code is in `src/main/java/io/github/sinaraz`. The public types are in the top package,
`model`, `error`, and `audio`. Everything in `internal` can change without warning.

## Licences

Judio is released under the MIT License.

| Dependency | Licence | Used for |
|---|---|---|
| JLayer 1.0.1 (`javazoom:jlayer`) | LGPL 2.1 | Decoding MP3 |
| FlatLaf 3.7.2 (`com.formdev:flatlaf`) | Apache 2.0 | The dark look of the graphical player only |

The library and the terminal player need only JLayer. FlatLaf is used only by the graphical player.
