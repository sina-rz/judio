/**
 * Extension points for decoding and audio output, and the built-in implementations.
 *
 * Implement {@link io.github.sinaraz.audio.AudioDecoder} to support a new file format,
 * and {@link io.github.sinaraz.audio.AudioOutput} to send audio somewhere other than a
 * sound card. Plug them in through {@link io.github.sinaraz.JudioPlayer.Builder}.
 */
package io.github.sinaraz.audio;
