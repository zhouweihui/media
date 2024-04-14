/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer;

import android.annotation.SuppressLint;
import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.Log;

/**
 * Default {@link MediaClock} which uses a renderer media clock and falls back to a {@link
 * StandaloneMediaClock} if necessary.
 */
/* package */ final class DefaultMediaClock implements MediaClock {
  private static final String TAG = "zhouwh";

  /** Listener interface to be notified of changes to the active playback parameters. */
  public interface PlaybackParametersListener {

    /**
     * Called when the active playback parameters changed. Will not be called for {@link
     * #setPlaybackParameters(PlaybackParameters)}.
     *
     * @param newPlaybackParameters The newly active playback parameters.
     */
    void onPlaybackParametersChanged(PlaybackParameters newPlaybackParameters);
  }

  private final StandaloneMediaClock standaloneClock;
  private final PlaybackParametersListener listener;

  @Nullable private Renderer rendererClockSource;
  @Nullable private MediaClock rendererClock;
  private boolean isUsingStandaloneClock;
  private boolean standaloneClockIsStarted;

  /**
   * Creates a new instance with a listener for playback parameters changes and a {@link Clock} to
   * use for the standalone clock implementation.
   *
   * @param listener A {@link PlaybackParametersListener} to listen for playback parameters changes.
   * @param clock A {@link Clock}.
   */
  public DefaultMediaClock(PlaybackParametersListener listener, Clock clock) {
    log( "api : new DefaultMediaClock() called with: listener = [" + listener + "], clock = [" + clock + "]");
    this.listener = listener;
    this.standaloneClock = new StandaloneMediaClock(clock);
    isUsingStandaloneClock = true;
  }

  /** Starts the standalone fallback clock. */
  public void start() {
    log( "api : start() called");
    standaloneClockIsStarted = true;
    standaloneClock.start();
  }

  /** Stops the standalone fallback clock. */
  public void stop() {
    log( "api : stop() called");
    standaloneClockIsStarted = false;
    standaloneClock.stop();
  }

  /**
   * Resets the position of the standalone fallback clock.
   *
   * @param positionUs The position to set in microseconds.
   */
  public void resetPosition(long positionUs) {
    log( "api : resetPosition() called with: positionUs = [" + positionUs + "]");
    standaloneClock.resetPosition(positionUs);
  }

  /**
   * Notifies the media clock that a renderer has been enabled. Starts using the media clock of the
   * provided renderer if available.
   *
   * @param renderer The renderer which has been enabled.
   * @throws ExoPlaybackException If the renderer provides a media clock and another renderer media
   *     clock is already provided.
   */
  public void onRendererEnabled(Renderer renderer) throws ExoPlaybackException {
    @Nullable MediaClock rendererMediaClock = renderer.getMediaClock();
    log( "api : onRendererEnabled() called with: renderer = [" + renderer
        + "] ; rendererMediaClock = [" + rendererMediaClock + "]");
    if (rendererMediaClock != null && rendererMediaClock != rendererClock) {
      if (rendererClock != null) {
        throw ExoPlaybackException.createForUnexpected(
            new IllegalStateException("Multiple renderer media clocks enabled."));
      }
      this.rendererClock = rendererMediaClock;
      this.rendererClockSource = renderer;
      rendererClock.setPlaybackParameters(standaloneClock.getPlaybackParameters());
    }
  }

  /**
   * Notifies the media clock that a renderer has been disabled. Stops using the media clock of this
   * renderer if used.
   *
   * @param renderer The renderer which has been disabled.
   */
  public void onRendererDisabled(Renderer renderer) {
    log( "api : onRendererDisabled() called with: renderer = [" + renderer + "]");
    if (renderer == rendererClockSource) {
      this.rendererClock = null;
      this.rendererClockSource = null;
      log(String.format("[onRendererDisabled] isUsingStandaloneClock update : %b -> %b",
          isUsingStandaloneClock, true));
      isUsingStandaloneClock = true;
    }
  }

  /**
   * Syncs internal clock if needed and returns current clock position in microseconds.
   *
   * @param isReadingAhead Whether the renderers are reading ahead.
   */
  public long syncAndGetPositionUs(boolean isReadingAhead) {
    // 每次都会更新isUsingStandaloneClock
    // 是否使用独立时钟还是渲染时钟。如果发生渲染时钟未生效或在切换过程中时间比独立时钟小，时间倒退，则使用独立时钟.并等待渲染器时钟的值增大。
    syncClocks(isReadingAhead);
    final long positionUs = getPositionUs();
    log( "api : syncAndGetPositionUs() called with: isReadingAhead = [" + isReadingAhead
        + "] ; isUsingStandaloneClock = [" + isUsingStandaloneClock + "] ; positionUs = ["
        + positionUs + "]");
    return positionUs;
  }

  // MediaClock implementation.

  @Override
  public long getPositionUs() {
    return isUsingStandaloneClock
        ? standaloneClock.getPositionUs()
        : Assertions.checkNotNull(rendererClock).getPositionUs();
  }

  @Override
  public void setPlaybackParameters(PlaybackParameters playbackParameters) {
    log( "api : setPlaybackParameters() called with: playbackParameters = [" + playbackParameters + "]");
    if (rendererClock != null) {
      rendererClock.setPlaybackParameters(playbackParameters);
      playbackParameters = rendererClock.getPlaybackParameters();
    }
    standaloneClock.setPlaybackParameters(playbackParameters);
  }

  @Override
  public PlaybackParameters getPlaybackParameters() {
    return rendererClock != null
        ? rendererClock.getPlaybackParameters()
        : standaloneClock.getPlaybackParameters();
  }

  private void syncClocks(boolean isReadingAhead) {
    if (shouldUseStandaloneClock(isReadingAhead)) {
      log(String.format("[syncClocks] isUsingStandaloneClock update : %b -> %b",
          isUsingStandaloneClock, true));
      isUsingStandaloneClock = true;
      if (standaloneClockIsStarted) {
        standaloneClock.start();
      }
      return;
    }
    // We are either already using the renderer clock or switching from the standalone to the
    // renderer clock, so it must be non-null.
    MediaClock rendererClock = Assertions.checkNotNull(this.rendererClock);
    long rendererClockPositionUs = rendererClock.getPositionUs();
    if (isUsingStandaloneClock) {
      // Ensure enabling the renderer clock doesn't jump backwards in time.
      final long standaloneClockPositionUs = standaloneClock.getPositionUs();
      log(String.format(
          "[syncClocks] : is renderer clock jump backwards = [%b] : renderClock(%d) , standaloneClock(%d)",
          rendererClockPositionUs < standaloneClockPositionUs, rendererClockPositionUs,
          standaloneClockPositionUs));
      if (rendererClockPositionUs < standaloneClockPositionUs) {
        // 如果之前是使用外部时钟，切换到渲染时钟，必须渲染时钟大于独立时钟，保证切换过程中时间不会倒退。否则会停止独立时钟，等渲染器时钟走了一段值后再切换到渲染器时钟？
        // stop会记录当前独立时钟的时间，此后没有开始的话，就会一直取得都是这个时间。
        standaloneClock.stop();
        return;
      }
      log(String.format("[syncClocks] isUsingStandaloneClock update : %b -> %b",
          isUsingStandaloneClock, false));
      isUsingStandaloneClock = false;
      if (standaloneClockIsStarted) {
        standaloneClock.start();
      }
    }
    // Continuously sync stand-alone clock to renderer clock so that it can take over if needed.
    standaloneClock.resetPosition(rendererClockPositionUs);
    PlaybackParameters playbackParameters = rendererClock.getPlaybackParameters();
    if (!playbackParameters.equals(standaloneClock.getPlaybackParameters())) {
      standaloneClock.setPlaybackParameters(playbackParameters);
      listener.onPlaybackParametersChanged(playbackParameters);
    }
  }

  private void log(String log) {
    Log.d(TAG, "DefaultMediaClock " + log);
  }

  private boolean shouldUseStandaloneClock(boolean isReadingAhead) {
    // Use the standalone clock if the clock providing renderer is not set or has ended. Also use
    // the standalone clock if the renderer is not ready and we have finished reading the stream or
    // are reading ahead to avoid getting stuck if tracks in the current period have uneven
    // durations. See: https://github.com/google/ExoPlayer/issues/1874.
    return rendererClockSource == null
        || rendererClockSource.isEnded()
        || (!rendererClockSource.isReady()
            && (isReadingAhead || rendererClockSource.hasReadStreamToEnd()));
  }
}
