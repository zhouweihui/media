package androidx.media3.demo.main;

import android.app.Activity;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

/**
 * description:
 *
 * @author wilfredzhou
 * @date 2024/3/18
 */
public class TestPlayerActivity extends AppCompatActivity {

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    final PlayerView view = new PlayerView(this);
    setContentView(view);
    String hlsUrl = "https://devstreaming-cdn.apple.com/videos/streaming/examples/bipbop_16x9/bipbop_16x9_variant.m3u8";
    ExoPlayer player = new ExoPlayer.Builder(this).build();
    view.setPlayer(player);
    player.setMediaItem(MediaItem.fromUri(hlsUrl),2345);
    player.setPlayWhenReady(false);
    player.prepare();
    getLifecycle().addObserver(new DefaultLifecycleObserver() {
      @Override
      public void onDestroy(@NonNull LifecycleOwner owner) {
        player.stop();
      }
    });
  }
}
