import io.github.sinaraz.JudioPlayer;
import io.github.sinaraz.PlaybackListener;
import io.github.sinaraz.model.Track;

void main() {
    var file = IO.readln("Enter audio path:");
    var path = Path.of(file);

    var finished = new CompletableFuture<Void>();

    try (var player = JudioPlayer.create()) {
        player.addListener(new PlaybackListener() {
            @Override
            public void onPlaybackCompleted(Track track) {
                finished.complete(null);
            }
        });

        player.addFiles(path);
        player.play();

        finished.join();
    }
}