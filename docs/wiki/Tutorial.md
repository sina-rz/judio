# Tutorial: build a music player with judio

This tutorial takes you from zero to a working player in six small steps. Each step is a complete
Java program. You can run it, read it, and change it.

Before you start, read [Core concepts](Concepts.md). It explains the ideas used below in about
ten minutes.

## Before you start

You need:

- **JDK 25.** Check with `java -version`. If no JDK 25 is installed, Gradle downloads one for
  the build automatically on first use (this needs network access once; delete the toolchain
  cache under `~/.gradle/jdks` to reclaim the disk space).
- **An audio file.** Use an MP3, WAV, AIFF, or AU file. Step 3 needs a file longer than 8
  seconds.
- **The judio project.** The programs live in `src/samples/java/io/github/sinaraz/samples/tutorial/`.

Run any step with Gradle. Replace the file name with your own:

```bash
./gradlew runSample -Psample=tutorial.Step1_PlayOneFile -PsampleArgs="song.mp3"
```

The `-PsampleArgs` value is split on spaces, so use a path without spaces. To use a path with
spaces, copy the file to a simpler name first.

Steps 1 to 4 use `JudioPlayer.create()`, which plays through your computer's sound device. If you
have no sound device, change `create()` to `JudioPlayer.builder().simulatedOutput().build()`. The
sound is then not played, but timing is real. Step 5 already uses the simulated device.
If you run Steps 1 to 4 on a machine with no sound device, the error listener reports something like
`no output line supports PCM_SIGNED`. That means the device is missing, so switch to the simulated
device as described above.

## Step 1: play one file

Your first program loads one file, plays it, and waits until it finishes. It needs three things:
a player, a listener that learns when the track ends, and a call to `play()`.

The `try (...)` block closes the player at the end. Closing releases the audio device and stops
background threads, so always close a player you no longer need.

```java
package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.Track;

/** Step 1: play one audio file from start to finish. */
public final class Step1_PlayOneFile {


    static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("usage: Step1_PlayOneFile <audio file>");
            return;
        }
        CompletableFuture<Void> finished = new CompletableFuture<>();

        try (JudioPlayer player = JudioPlayer.create()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onPlaybackCompleted(Track track) {
                    System.out.println("Finished: " + track.displayName());
                    finished.complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    finished.completeExceptionally(new IllegalStateException(error.message()));
                }
            });

            player.addFiles(Path.of(args[0]));
            player.play();
            finished.get();   // rethrows the playback error, if there was one
        }
    }
}
```

Run it and you hear the file. The `CompletableFuture` is only there so `main` does not exit before
the music ends, and it rethrows the error if playback fails. A real application does not wait like this. It reacts to events instead, as in
Step 4.

## Step 2: a queue with next and previous

Most players hold a list of tracks. In judio this list is the **queue**. You add files to it,
then move through it with `next()` and `previous()`.

This program prints the queue, plays the first entry, moves to the next one, and then goes back.
The `Thread.sleep` calls only pause so you can hear each change. They are not part of the API.

```java
package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.model.Track;

/** Step 2: build a queue, play it, and move between entries. */
public final class Step2_Queue {


    static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            System.out.println("usage: Step2_Queue <file> <file> [more files]");
            return;
        }
        try (JudioPlayer player = JudioPlayer.create()) {
            List<Path> files = new ArrayList<>();
            for (String arg : args) {
                files.add(Path.of(arg));
            }
            player.addFiles(files);

            List<Track> queue = player.queue();
            for (int i = 0; i < queue.size(); i++) {
                System.out.println(i + ": " + queue.get(i).displayName());
            }

            player.play();
            Thread.sleep(3_000);

            player.next();
            Thread.sleep(3_000);
            System.out.println("Now at index " + player.queueSnapshot().currentIndex());

            player.previous();
            Thread.sleep(1_000);
            System.out.println("Now at index " + player.queueSnapshot().currentIndex());
        }
    }
}
```

`queueSnapshot()` describes the queue position. It is a single value, so the index it reports
always matches a single moment in time.

## Step 3: pause, seek, volume, and speed

The transport and mixing controls are simple method calls on the player. This program changes
them while a track plays and prints the state after each change.

The file must be longer than 8 seconds, because the program seeks to the 5-second mark.

```java
package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.time.Duration;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.RepeatMode;

/** Step 3: pause, seek, change volume and speed, and read the snapshot. */
public final class Step3_Controls {


    static void main(String[] args) throws InterruptedException {
        if (args.length != 1) {
            System.out.println("usage: Step3_Controls <audio file longer than 8 seconds>");
            return;
        }
        try (JudioPlayer player = JudioPlayer.create()) {
            player.addFiles(Path.of(args[0]));
            player.setVolume(0.8);
            player.setRepeat(RepeatMode.ALL);
            player.play();
            Thread.sleep(1_000);

            player.pause();
            print("paused", player.snapshot());

            player.resume();
            player.seek(Duration.ofSeconds(5));
            player.setPlaybackSpeed(1.25);
            Thread.sleep(1_000);
            print("after seek and speed", player.snapshot());

            player.setMuted(true);
            player.stop();
            print("stopped and muted", player.snapshot());
        }
    }

    private static void print(String label, PlaybackSnapshot s) {
        System.out.printf("%-20s state=%-8s position=%.1fs volume=%.2f muted=%b speed=%.2f%n",
                label, s.state(), s.position().toMillis() / 1000.0, s.volume(), s.muted(), s.speed());
    }
}
```

Each method changes the player's current state. `snapshot()` reads that state back and returns
it as an immutable value. Use the snapshot to draw a screen, and call the control methods to
change playback.

The state can read `STOPPING` for a moment after `stop()`. The audio thread finishes the change in
the background, so the state settles a few milliseconds later. Listen to `onStateChanged` if you
need the final state.

## Step 4: events, tags, and cover art

A player that only accepts commands cannot show what is happening. A **listener** receives events
such as a track loading, a progress update, a finished track, or an error.

This program prints the track details when the file loads, counts progress updates, and reads the
cover art, if the file has one. Tags such as title and artist come from the file's ID3 metadata.

```java
package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.Track;

/** Step 4: react to events, and read tags and cover art. */
public final class Step4_Events {


    static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.out.println("usage: Step4_Events <audio file>");
            return;
        }
        Path file = Path.of(args[0]);
        CompletableFuture<Void> finished = new CompletableFuture<>();
        AtomicInteger progressUpdates = new AtomicInteger();

        try (JudioPlayer player = JudioPlayer.create()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackLoaded(Track track, AudioInfo info) {
                    System.out.printf("Loaded %s by %s: %d Hz, %d channel(s), %s%n",
                            Objects.toString(track.title(), track.displayName()),
                            Objects.toString(track.artist(), "unknown artist"),
                            info.sampleRate(), info.channels(), info.duration());
                }

                @Override
                public void onProgress(PlaybackSnapshot snapshot) {
                    progressUpdates.incrementAndGet();
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    finished.complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    finished.completeExceptionally(new IllegalStateException(error.message()));
                }
            });

            player.addFiles(file);
            Optional<CoverArt> cover = player.coverArt(player.queue().get(0));
            System.out.println(cover.map(coverArt -> "Cover art: " + coverArt.mimeType() + ", " + coverArt.data().length + " bytes").orElse("No cover art"));

            player.play();
            finished.get();   // rethrows the playback error, if there was one
            System.out.println("Progress updates received: " + progressUpdates.get());
        }
    }
}
```

Listener callbacks run on a separate event thread, so you can update a screen or write to a file
in them. Do not make them wait for a long time, or events will queue up behind them.

## Step 5: configuration and shuffle

The last step puts several choices together. It sets a custom configuration, uses the simulated
device, seeds the random generator so the shuffle order repeats, and plays a queue in shuffled
order.

```java
package io.github.sinaraz.samples.tutorial;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackConfig;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

/** Step 5: custom configuration, a simulated device, and a repeatable shuffle order. */
public final class Step5_CustomPlayer {


    static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("usage: Step5_CustomPlayer <audio file> [more files]");
            return;
        }
        PlaybackConfig config = PlaybackConfig.builder()
                .progressInterval(Duration.ofMillis(250))
                .previousRestartThreshold(Duration.ofSeconds(2))
                .build();
        // One future per queued track. Each completes on success or failure, so one bad file does not
        // stop the wait for the others.
        Map<String, CompletableFuture<Void>> outcomes = new ConcurrentHashMap<>();

        try (JudioPlayer player = JudioPlayer.builder()
                .config(config)
                .simulatedOutput()
                .random(new Random(42))
                .build()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackChanged(Track previous, Track current) {
                    System.out.println("Now playing: " + (current == null ? "nothing" : current.displayName()));
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    outcomes.get(track.id()).complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    System.out.println("Error: " + error.message());
                    if (error.track() != null) {
                        outcomes.get(error.track().id()).complete(null);
                    }
                }
            });

            List<Track> tracks = player.addFiles(Arrays.stream(args).map(Path::of).toList());
            for (Track track : tracks) {
                outcomes.put(track.id(), new CompletableFuture<>());
            }
            player.setRepeat(RepeatMode.OFF);
            player.setShuffle(true);
            player.play();
            // Waits for every track; the timeout scales with the number of files.
            CompletableFuture.allOf(outcomes.values().toArray(new CompletableFuture<?>[0]))
                    .get(60L * tracks.size(), TimeUnit.SECONDS);
        }
    }
}
```

- `PlaybackConfig` holds tuning values. Set only the ones you need. The rest keep their defaults.
- `simulatedOutput()` plays in real time without a sound device. Tests and servers use it.
- A fixed `Random` seed gives the same shuffle order every run.
- Each file gets its own `CompletableFuture`, completed on success or failure. The program waits for
  all of them, so one failed file does not stop the wait for the others.

## Step 6: streams, seeking, and pitch-preserving speed

The last step streams an MP3 over HTTPS from a public host. It seeks two minutes into the song,
then plays the last part at 1.5 times the speed with the pitch kept. It needs an internet
connection.

```java
package io.github.sinaraz.samples.tutorial;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.Track;

/** Step 6: stream a hosted MP3, seek into it, then change its speed with the pitch kept. */
public final class Step6_StreamsAndPitch {

    /** A public MP3 of about 2 minutes 13 seconds. */
    static final URI SONG = URI.create(
            "https://file-examples.com/storage/fe9ac44c8a6acc7d7989e28/2017/11/file_example_MP3_5MG.mp3");


    static void main(String[] args) throws Exception {
        CompletableFuture<AudioInfo> loaded = new CompletableFuture<>();
        CompletableFuture<Void> finished = new CompletableFuture<>();
        try (JudioPlayer player = JudioPlayer.builder().simulatedOutput().build()) {
            player.addListener(new PlaybackListener() {
                @Override
                public void onTrackLoaded(Track track, AudioInfo info) {
                    System.out.println("Loaded " + track.displayName() + ", " + info.duration());
                    loaded.complete(info);
                }

                @Override
                public void onPlaybackCompleted(Track track) {
                    finished.complete(null);
                }

                @Override
                public void onError(PlaybackError error) {
                    IllegalStateException failure = new IllegalStateException(error.message());
                    loaded.completeExceptionally(failure);
                    finished.completeExceptionally(failure);
                }
            });

            player.addUrl(SONG);
            player.setPreservePitch(true);         // keep the pitch when the speed changes
            player.setPlaybackSpeed(1.5);          // 50% faster, same key
            player.play();
            loaded.get(60, TimeUnit.SECONDS);
            player.seek(Duration.ofSeconds(120));  // the server supports ranges, so this reconnects at 2:00
            System.out.println("Playing the last part of the song at 1.5x with the pitch kept.");
            finished.get(60, TimeUnit.SECONDS);
        }
    }
}
```

- `addUrl(URI)` queues a stream. judio fetches it while it plays, and it reads no tags from it.
- `seek(...)` on a stream reconnects at the matching byte position with an HTTP range request. The
  server must report the file size and support ranges. Otherwise the call throws
  `SeekNotSupportedException`.
- `setPreservePitch(true)` makes speed changes keep the pitch. Without it, `setPlaybackSpeed(1.5)`
  would also raise the pitch.
- The listener completes a future when the stream ends, so the program waits for that.

## Where to go next

- Read the [README](../../README.md) for the full list of features, supported formats, and the
  console and GUI programs.
- Read the API in the `io.github.sinaraz` package. `JudioPlayer` is the entry point, and
  `PlaybackListener` lists every event.
- Run `./gradlew runGui` to see a complete Swing player built on this same API. It has an
  **Open URL** button for streams and a **Preserve pitch** checkbox.
