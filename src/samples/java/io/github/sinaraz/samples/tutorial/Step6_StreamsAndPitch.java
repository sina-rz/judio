import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.AudioInfo;
import io.github.sinaraz.model.PlaybackError;
import io.github.sinaraz.model.Track;


static final URI SONG = URI.create(
        "https://music.cdn.asset.aparat.com/aparat-music/AM-dHJhY2stMHFOc0U4NFNGQUxQZDRvaDhYajhsNA-128-1786854787.mp3?wmsAuthSign=eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdiI6IndlYl8xLjAuNSIsImRvIjoid2luZG93cyIsImR0IjoiZGVza3RvcCIsInBpIjowLCJzdCI6InVuc3Vic2NyaWJlZCIsInRpIjo1OTY3MzgsInVpIjo2NDk0MjF9.X6TN-ZFJdmm9YltcSfNewq4pDqtxXV26UzmSzIqQw5w"
);

void main() throws Exception {
    CompletableFuture<AudioInfo> loaded = new CompletableFuture<>();
    CompletableFuture<Void> finished = new CompletableFuture<>();
    try (JudioPlayer player = JudioPlayer.create()) {
        player.addListener(new PlaybackListener() {
            @Override
            public void onTrackLoaded(Track track, AudioInfo info) {
                System.out.println("Loaded " + track.displayName() + ", " + info.duration().getSeconds() + " seconds");
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

