/**
 * judio: a pure-Java audio playback engine.
 *
 * Start with {@link io.github.sinaraz.JudioPlayer}. It queues files, plays them, and
 * reports progress and state changes to a {@link io.github.sinaraz.PlaybackListener}.
 *
 * The library needs no native code and no media framework. MP3 is decoded with JLayer,
 * and WAV, AIFF, and AU are read directly or through the JDK.
 */
package io.github.sinaraz;
