package io.github.sinaraz;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;
import java.util.stream.Stream;

import io.github.sinaraz.audio.AudioOutput;
import io.github.sinaraz.audio.DecoderFactory;
import io.github.sinaraz.audio.DecoderProvider;
import io.github.sinaraz.audio.SimulatedAudioOutput;
import io.github.sinaraz.audio.SystemAudioOutput;
import io.github.sinaraz.error.PlaybackStateException;
import io.github.sinaraz.error.QueueException;
import io.github.sinaraz.error.SeekNotSupportedException;
import io.github.sinaraz.internal.engine.AudioPlaybackEngine;
import io.github.sinaraz.internal.engine.PlaybackEventBus;
import io.github.sinaraz.internal.lifecycle.CloseDeadline;
import io.github.sinaraz.internal.metadata.MetadataService;
import io.github.sinaraz.internal.queue.QueueManager;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.CoverArt;
import io.github.sinaraz.model.MediaLocation;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.PlaybackSnapshot;
import io.github.sinaraz.model.PlaybackState;
import io.github.sinaraz.model.QueueSnapshot;
import io.github.sinaraz.model.RemoveResult;
import io.github.sinaraz.model.RepeatMode;
import io.github.sinaraz.model.Track;

/**
 * The application-facing API. It coordinates three collaborators:
 *
 * <ul>
 *   <li>{@link QueueManager}: what plays next, and in what order.</li>
 *   <li>{@link AudioPlaybackEngine}: how the current track is decoded and played.</li>
 *   <li>{@link MetadataService}: tags, read in the background.</li>
 * </ul>
 *
 * <p>Automatic advancement (end of track, errors) is implemented here, as an internal listener on
 * the event thread. It is not done inside the engine, which therefore knows nothing about queues.
 *
 * <p>Methods that touch both the queue and the engine run under one monitor so that composite
 * operations (for example "remove current, then unload") are not interleaved with each other.
 * Listeners are never called while that monitor is held.
 *
 * <p>{@link #previous()} implements the restart rule: if the position is past
 * {@link PlaybackConfig#previousRestartThreshold()}, the current track restarts; otherwise the
 * previous queue entry is loaded.
 */
public final class JudioPlayer implements AutoCloseable {

    private static final List<String> AUDIO_EXTENSIONS =
            List.of("mp3", "ogg", "oga", "opus", "wav", "wave", "aif", "aiff", "aifc", "au", "snd");

    private final PlaybackConfig config;
    private final QueueManager queue;
    private final PlaybackEventBus bus;
    private final MetadataService metadata;
    private final AudioPlaybackEngine engine;
    private final Object gate = new Object();
    private int consecutiveFailures; // guarded by gate
    private boolean closed;          // guarded by gate

    private JudioPlayer(PlaybackConfig config, DecoderProvider decoders,
                         Supplier<AudioOutput> outputs, RandomGenerator random) {
        this.config = config;
        this.queue = new QueueManager(random);
        this.bus = new PlaybackEventBus(config.closeTimeout());
        this.metadata = new MetadataService();
        this.engine = new AudioPlaybackEngine(config, decoders, outputs, bus, queue::snapshotInfo);
        bus.addListener(new AutoAdvance());
    }

    /**
     * Returns a player that plays through the default system audio device with the default
     * configuration. Equivalent to {@code builder().build()}.
     *
     * @return a new player; close it when you are done
     */
    public static JudioPlayer create() {
        return builder().build();
    }

    /**
     * Returns a builder for a player with custom configuration, audio output, or decoders.
     *
     * @return a new builder with default settings
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builds a {@link JudioPlayer}. Every setting is optional; the defaults are the default
     * {@link PlaybackConfig}, the standard {@link DecoderFactory}, the system audio device, and
     * {@link RandomGenerator#getDefault()} for shuffle.
     */
    public static final class Builder {

        private PlaybackConfig config = PlaybackConfig.defaults();
        private DecoderProvider decoders = DecoderFactory.standard();
        private Supplier<AudioOutput> output;
        private boolean simulated;
        private RandomGenerator random = RandomGenerator.getDefault();

        private Builder() {}

        /**
         * Sets the playback configuration.
         *
         * @param config the configuration to use
         * @return this builder
         */
        public Builder config(PlaybackConfig config) {
            this.config = Objects.requireNonNull(config, "config");
            return this;
        }

        /**
         * Sets how files are opened. Replace the default to add a custom format.
         *
         * @param decoders the provider that opens files
         * @return this builder
         */
        public Builder decoders(DecoderProvider decoders) {
            this.decoders = Objects.requireNonNull(decoders, "decoders");
            return this;
        }

        /**
         * Sets where audio goes. The supplier is called once for each audio format that is played.
         *
         * @param output creates an {@link AudioOutput}
         * @return this builder
         */
        public Builder output(Supplier<AudioOutput> output) {
            this.output = Objects.requireNonNull(output, "output");
            this.simulated = false;
            return this;
        }

        /**
         * Plays through a simulated real-time device: no sound is produced, but timing is real. Use it
         * on machines without a sound card, and in tests.
         *
         * @return this builder
         */
        public Builder simulatedOutput() {
            this.output = null;
            this.simulated = true;
            return this;
        }

        /**
         * Sets the random source used by shuffle. Pass a seeded generator for a repeatable order.
         *
         * @param random the random source
         * @return this builder
         */
        public Builder random(RandomGenerator random) {
            this.random = Objects.requireNonNull(random, "random");
            return this;
        }

        /**
         * Creates the player. Close it with {@link JudioPlayer#close()} when you are done.
         *
         * @return a new player
         */
        public JudioPlayer build() {
            // Capture the final config value, not the builder: mutating this
            // builder after build() must not change an already-built player.
            PlaybackConfig finalConfig = config;
            Supplier<AudioOutput> device;
            if (output != null) {
                device = output;
            } else if (simulated) {
                device = () -> new SimulatedAudioOutput(finalConfig.outputBufferBytes());
            } else {
                device = () -> new SystemAudioOutput(finalConfig.outputBufferBytes());
            }
            return new JudioPlayer(finalConfig, decoders, device, random);
        }
    }

    /**
     * Registers a listener for state, track, progress, queue, metadata, completion, and error events.
     * Listeners run on a dedicated event thread, never on the audio thread. Progress and consecutive
     * queue-change snapshots may be coalesced when that thread falls behind. An exception thrown
     * by a listener is logged and does not stop playback.
     *
     * @param listener the listener to add; adding the same instance twice has no extra effect
     */
    public void addListener(PlaybackListener listener) {
        bus.addListener(listener);
    }

    /**
     * Unregisters a listener. Events already queued for it may still be delivered.
     *
     * @param listener the listener to remove
     */
    public void removeListener(PlaybackListener listener) {
        bus.removeListener(listener);
    }

    // ------------------------------------------------------------------ queue

    /**
     * Adds files to the end of the queue. Every path is checked before any is added, so a bad
     * path leaves the queue unchanged. Tags are read in the background; a track is playable
     * before its tags are known.
     *
     * @param files audio files to add, in play order
     * @return the new queue entries, in the same order as {@code files}
     * @throws QueueException if a path is missing or is not a regular file
     */
    public List<Track> addFiles(List<Path> files) {
        Objects.requireNonNull(files, "files");
        requireOpen("add");
        List<Track> created = new ArrayList<>(files.size());
        for (Path file : files) {
            if (file == null) {
                throw new QueueException("add", "files must not contain null");
            }
            if (!Files.isRegularFile(file)) {
                throw new QueueException("add", "not a file: " + file);
            }
            created.add(Track.fromPath(file));
        }
        if (created.isEmpty()) {
            return created;
        }
        synchronized (gate) {
            requireOpenLocked("add");
            queue.addAll(created);
            publishQueue();
        }
        created.forEach(this::loadTagsAsync);
        return created;
    }

    /**
     * Inserts a file at {@code index} in the queue (0..size). Tags are read in the background,
     * as with {@link #addFiles(List)}.
     *
     * @param index zero-based position the new entry should occupy
     * @param file  the audio file to insert
     * @return the new queue entry
     * @throws QueueException if the path is missing, is not a regular file, or the index is out of range
     */
    public Track insertFile(int index, Path file) {
        requireOpen("insert");
        if (file == null) {
            throw new QueueException("insert", "file must not be null");
        }
        if (!Files.isRegularFile(file)) {
            throw new QueueException("insert", "not a file: " + file);
        }
        Track created = Track.fromPath(file);
        synchronized (gate) {
            requireOpenLocked("insert");
            queue.insert(index, created);
            publishQueue();
        }
        loadTagsAsync(created);
        return created;
    }

    /**
     * Adds an {@code http} or {@code https} audio stream to the end of the queue. The stream is
     * fetched while it plays, so nothing is downloaded in full first. Tags are not read from
     * streams. Only the formats that can be decoded from a stream are playable; see the README.
     *
     * <p><b>Trust boundary:</b> playing the entry opens an outbound connection to {@code uri}
     * from this host, following redirects. Do not queue untrusted addresses (for example
     * loopback, link-local metadata, or LAN services) without validating them first.
     *
     * @param uri absolute http or https address of the audio
     * @return the new queue entry
     * @throws IllegalArgumentException if the URI is not an absolute http or https address with a host
     */
    public Track addUrl(URI uri) {
        requireOpen("add-url");
        Track created = Track.fromUri(uri);
        synchronized (gate) {
            requireOpenLocked("add-url");
            queue.addAll(List.of(created));
            publishQueue();
        }
        return created;
    }

    /**
     * Adds files to the end of the queue. Convenience form of {@link #addFiles(List)}.
     *
     * @param files audio files to add, in play order
     * @return the new queue entries, in the same order as {@code files}
     * @throws QueueException if a path is missing or is not a regular file
     */
    public List<Track> addFiles(Path... files) {
        return addFiles(List.of(files));
    }

    /**
     * Adds every supported audio file directly inside {@code directory}, sorted by file name.
     * Subfolders are not searched.
     *
     * @param directory the folder to scan
     * @return the new queue entries
     * @throws QueueException if the path is not a folder, cannot be read, or holds no audio files
     */
    public List<Track> addDirectory(Path directory) {
        if (!Files.isDirectory(directory)) {
            throw new QueueException("add-dir", "not a directory: " + directory);
        }
        List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries
                    .filter(Files::isRegularFile)
                    .filter(p -> AUDIO_EXTENSIONS.contains(extensionOf(p)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new QueueException("add-dir", "cannot read directory " + directory + ": " + e.getMessage());
        }
        if (files.isEmpty()) {
            throw new QueueException("add-dir", "no audio files found in " + directory);
        }
        return addFiles(files);
    }

    /**
     * Removes the entry at {@code index}. Removing the current entry stops playback and
     * unloads the track; the next entry is not started.
     *
     * @param index zero-based position in the queue
     * @return what was removed and whether it was the current entry
     */
    public RemoveResult remove(int index) {
        synchronized (gate) {
            requireOpenLocked("remove");
            RemoveResult result = queue.remove(index);
            if (result.currentRemoved()) {
                engine.unload();
            }
            publishQueue();
            return result;
        }
    }

    /**
     * Moves a queue entry to a new position. The entry that is playing stays the current one.
     *
     * @param from zero-based index of the entry to move
     * @param to   zero-based index it should end up at
     */
    public void move(int from, int to) {
        synchronized (gate) {
            requireOpenLocked("move");
            queue.move(from, to);
            publishQueue();
        }
    }

    /**
     * Stops playback, unloads the current track, and empties the queue.
     */
    public void clear() {
        synchronized (gate) {
            requireOpenLocked("clear");
            engine.unload();
            queue.clear();
            consecutiveFailures = 0;
            publishQueue();
        }
    }

    /**
     * Returns a copy of the queue in the order the user sees it.
     *
     * @return the current queue entries
     */
    public List<Track> queue() {
        return queue.snapshot();
    }

    /**
     * Returns the queue position, size, repeat mode, and shuffle flag as one consistent value.
     *
     * @return a snapshot of the queue state
     */
    public QueueSnapshot queueSnapshot() {
        return queue.snapshotInfo();
    }

    // -------------------------------------------------------------- transport

    /**
     * Starts playback. If nothing is loaded, the current queue entry is loaded first, or the first
     * entry when no entry is current.
     *
     * @throws QueueException if the queue is empty
     * @throws PlaybackStateException if playback cannot start in the current state
     */
    public void play() {
        synchronized (gate) {
            if (queue.size() == 0) {
                throw new QueueException("play", "the queue is empty");
            }
            PlaybackState state = engine.snapshot().state();
            if (state == PlaybackState.EMPTY || state == PlaybackState.ERROR) {
                consecutiveFailures = 0;
                Track current = queue.current().orElseGet(() -> queue.playAt(0));
                engine.load(current);
            }
            engine.play();
        }
    }

    /**
     * Loads a queue entry without starting playback, so {@link #play()} starts it at once.
     *
     * @param index zero-based position in the queue
     */
    public void prepare(int index) {
        synchronized (gate) {
            requireOpenLocked("prepare");
            Track track = queue.playAt(index);
            consecutiveFailures = 0;
            engine.load(track);
            publishQueue();
        }
    }

    /**
     * Makes the entry at {@code index} current and starts it from the beginning.
     *
     * @param index zero-based position in the queue
     */
    public void playAt(int index) {
        synchronized (gate) {
            requireOpenLocked("play-at");
            Track track = queue.playAt(index);
            consecutiveFailures = 0;
            engine.load(track);
            engine.play();
            publishQueue();
        }
    }

    /**
     * Pauses playback. The position stays where it is until {@link #resume()} is called.
     *
     * @throws PlaybackStateException if nothing is playing
     */
    public void pause() {
        engine.pause();
    }

    /**
     * Resumes paused playback from the paused position.
     *
     * @throws PlaybackStateException if playback is not paused
     */
    public void resume() {
        engine.resume();
    }

    /**
     * Stops playback and returns the position to the start of the current track. The track
     * stays loaded, so {@link #play()} starts it again.
     *
     * @throws PlaybackStateException if there is nothing to stop
     */
    public void stop() {
        engine.stop();
    }

    /**
     * Moves to the next entry in play order and starts it.
     *
     * @return {@code false} if there is no next entry; playback is then stopped
     */
    public boolean next() {
        synchronized (gate) {
            requireOpenLocked("next");
            Optional<Track> next = queue.next();
            if (next.isPresent()) {
                consecutiveFailures = 0;
                engine.load(next.get());
                engine.play();
                publishQueue();
                return true;
            }
            PlaybackState state = engine.snapshot().state();
            if (state.hasLoadedTrack()) {
                engine.stop();
            }
            publishQueue();
            return false;
        }
    }

    /**
     * Goes back one step. If the position is past {@link PlaybackConfig#previousRestartThreshold()},
     * the current track restarts from the beginning. Otherwise, the previous queue entry starts.
     */
    public void previous() {
        synchronized (gate) {
            requireOpenLocked("previous");
            PlaybackSnapshot snap = engine.snapshot();
            boolean pastThreshold = snap.position().compareTo(config.previousRestartThreshold()) > 0;
            if (pastThreshold && snap.state() != PlaybackState.ENDED && snap.state().hasLoadedTrack()) {
                engine.seek(Duration.ZERO);
                return;
            }
            Optional<Track> previous = queue.previous();
            if (previous.isPresent()) {
                consecutiveFailures = 0;
                engine.load(previous.get());
                engine.play();
            } else if (snap.state().hasLoadedTrack()) {
                engine.seek(Duration.ZERO);
            }
            publishQueue();
        }
    }

    /**
     * Moves to an absolute position within the current track. Positions outside the track are
     * clamped to its start or end.
     *
     * @param position time from the start of the track
     * @throws PlaybackStateException if no track is loaded
     * @throws SeekNotSupportedException if the current source cannot be repositioned
     */
    public void seek(Duration position) {
        engine.seek(position);
    }

    /**
     * Moves forward or backward from the current position. The result is clamped to the track.
     *
     * @param offset how far to move; negative values move backward
     * @throws PlaybackStateException if no track is loaded
     * @throws SeekNotSupportedException if the current source cannot be repositioned
     */
    public void seekRelative(Duration offset) {
        engine.seekRelative(offset);
    }

    // ---------------------------------------------------------------- controls

    /**
     * Sets the output volume. Values outside [0, 1] are clamped to that range.
     *
     * @param volume linear volume: 0.0 is silent and 1.0 is full volume
     * @throws IllegalArgumentException if {@code volume} is NaN
     */
    public void setVolume(double volume) {
        engine.setVolume(volume);
    }

    /**
     * Mutes or unmutes the output. Muting keeps the volume setting, so unmuting restores it.
     *
     * @param muted {@code true} to silence the output
     */
    public void setMuted(boolean muted) {
        engine.setMuted(muted);
    }

    /**
     * Sets the playback speed. By default, this changes the pitch too, as with a tape played faster or
     * slower; {@link #setPreservePitch(boolean)} keeps the pitch instead. Values are clamped to the speed
     * range in {@link PlaybackConfig}.
     *
     * @param speed speed multiplier; 1.0 is normal speed
     * @throws IllegalArgumentException if {@code speed} is NaN or infinite
     */
    public void setPlaybackSpeed(double speed) {
        engine.setPlaybackSpeed(speed);
    }

    /**
     * Chooses how speed changes are rendered. When {@code false} (the default) speed also changes pitch,
     * like a tape. When {@code true} the tempo changes but the pitch is kept, using WSOLA time-stretching,
     * which costs a little extra CPU and about 35 ms of latency.
     *
     * @param preserve {@code true} to keep pitch when the speed changes
     */
    public void setPreservePitch(boolean preserve) {
        engine.setPreservePitch(preserve);
    }

    /**
     * Whether speed changes keep the pitch. See {@link #setPreservePitch(boolean)}.
     *
     * @return {@code true} if pitch is preserved
     */
    public boolean isPreservePitch() {
        return engine.isPreservePitch();
    }

    /**
     * Sets the stereo position. Uses a constant-power law, so the loudness stays even as the
     * sound moves.
     *
     * @param pan -1.0 for fully left, 0.0 for centre, 1.0 for fully right; values outside are clamped
     * @throws IllegalArgumentException if {@code pan} is NaN
     */
    public void setPan(double pan) {
        engine.setPan(pan);
    }

    /**
     * Sets what happens at the end of the queue or track.
     *
     * @param mode {@code OFF} stops after the last entry, {@code ONE} replays the current entry,
     *             and {@code ALL} wraps back to the first
     */
    public void setRepeat(RepeatMode mode) {
        synchronized (gate) {
            requireOpenLocked("set-repeat");
            queue.setRepeat(mode);
            publishQueue();
        }
    }

    /**
     * Turns shuffle on or off. The visible queue does not change; only the play order does.
     *
     * @param enabled {@code true} to play the queue in random order
     */
    public void setShuffle(boolean enabled) {
        synchronized (gate) {
            requireOpenLocked("set-shuffle");
            queue.setShuffle(enabled);
            publishQueue();
        }
    }

    /**
     * Returns the current state of playback as one consistent value.
     *
     * @return the current playback state
     */
    public PlaybackSnapshot snapshot() {
        return engine.snapshot();
    }

    /**
     * Reads the embedded cover image for a track from its file. This performs file I/O
     * synchronously on the calling thread (results are cached per file while it is
     * unchanged), so call it from a background thread in a UI.
     *
     * @param track the track to read
     * @return the cover, or empty when the file has none
     * @throws IOException if the file cannot be read
     */
    public Optional<CoverArt> coverArt(Track track) throws IOException {
        if (!(track.location() instanceof MediaLocation.LocalFile(Path path))) {
            return Optional.empty(); // network streams carry no readable file
        }
        return metadata.loadCoverArt(path);
    }

    // ------------------------------------------------------------- lifecycle

    /**
     * Stops playback, releases the audio device, and shuts down background threads. Safe to call
     * more than once. All background-worker waits share one end-to-end deadline of
     * {@link PlaybackConfig#closeTimeout()}. The total call can exceed it while acquiring internal
     * locks or completing synchronous resource cleanup.
     */
    @Override
    public void close() {
        CloseDeadline deadline = CloseDeadline.after(config.closeTimeout());
        synchronized (gate) {
            if (closed) {
                return;
            }
            closed = true;
        }
        RuntimeException failure = null;
        try {
            engine.close(deadline);
        } catch (RuntimeException e) {
            failure = e;
        }
        try {
            metadata.close(deadline);
        } catch (RuntimeException e) {
            failure = appendCloseFailure(failure, e);
        }
        try {
            bus.close(deadline);
        } catch (RuntimeException e) {
            failure = appendCloseFailure(failure, e);
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ------------------------------------------------------------- internals

    /** Fails fast once the player is closed. Safe to call while holding {@code gate}. */
    private void requireOpen(String operation) {
        synchronized (gate) {
            requireOpenLocked(operation);
        }
    }

    /** Caller must hold {@code gate}. */
    private void requireOpenLocked(String operation) {
        if (closed) {
            throw new PlaybackStateException(operation, PlaybackState.CLOSED, "player is closed");
        }
    }

    private static RuntimeException appendCloseFailure(RuntimeException primary, RuntimeException next) {
        if (primary == null) {
            return next;
        }
        if (primary != next) {
            primary.addSuppressed(next);
        }
        return primary;
    }

    private void publishQueue() {
        bus.publishQueueChanged(queue::snapshotView);
    }

    private void loadTagsAsync(Track track) {
        if (track.isRemote()) {
            return; // tags are not read from network streams
        }
        synchronized (gate) {
            if (closed) {
                return; // never submit work to a shut-down metadata pool
            }
        }
        metadata.loadAsync(track).thenAccept(result -> {
            synchronized (gate) {
                if (closed) {
                    return;
                }
                queue.update(result.trackId(), t -> {
                    if (result.error() != null) {
                        return t.withMetadataFailure(result.error());
                    }
                    if (result.metadata() != null) {
                        return t.withMetadata(result.metadata());
                    }
                    return t.withMetadataFailure("metadata unavailable");
                }).ifPresent(updated -> bus.publish(l -> l.onTrackMetadataChanged(updated)));
            }
        });
    }

    private static String extensionOf(Path p) {
        String name = p.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /**
     * Internal listener implementing queue advancement. It runs on the event thread, so it may
     * call the engine (which never waits for events) but must not wait for the event thread.
     */
    private final class AutoAdvance implements PlaybackListener {

        @Override
        public void onTrackLoaded(Track track, AudioInfo info) {
            synchronized (gate) {
                consecutiveFailures = 0;
                if (queue.update(track.id(), t -> t.withAudioInfo(info)).isPresent()) {
                    publishQueue();
                }
            }
        }

        @Override
        public void onPlaybackCompleted(Track track) {
            synchronized (gate) {
                if (closed) {
                    return;
                }
                Optional<Track> next = queue.onTrackEnded();
                if (next.isPresent()) {
                    engine.load(next.get());
                    engine.play();
                }
                publishQueue();
            }
        }

        @Override
        public void onError(PlaybackError error) {
            synchronized (gate) {
                if (closed) {
                    return;
                }
                if (error.track() != null) {
                    queue.update(error.track().id(), t -> t.withErrorMessage(error.message()));
                }
                // Skip to the next entry, but never loop forever over a queue of broken files.
                boolean advance = config.autoAdvanceOnError()
                        && error.track() != null
                        && ++consecutiveFailures < queue.size();
                if (advance) {
                    Optional<Track> next = queue.next();
                    if (next.isPresent()) {
                        engine.load(next.get());
                        engine.play();
                    }
                }
                publishQueue();
            }
        }
    }
}
