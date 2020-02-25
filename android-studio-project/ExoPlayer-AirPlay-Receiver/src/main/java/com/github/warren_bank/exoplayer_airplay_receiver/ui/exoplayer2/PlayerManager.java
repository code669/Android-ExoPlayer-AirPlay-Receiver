package com.github.warren_bank.exoplayer_airplay_receiver.ui.exoplayer2;

import com.github.warren_bank.exoplayer_airplay_receiver.R;

import android.content.Context;
import android.net.Uri;
import androidx.annotation.Nullable;
import android.view.KeyEvent;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.DiscontinuityReason;
import com.google.android.exoplayer2.Player.EventListener;
import com.google.android.exoplayer2.Player.TimelineChangeReason;
import com.google.android.exoplayer2.RenderersFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.ConcatenatingMediaSource;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.RawResourceDataSource;

import java.util.ArrayList;

/** Manages players and an internal media queue */
public final class PlayerManager implements EventListener {

  private PlayerView playerView;
  private ArrayList<VideoSource> mediaQueue;
  private ConcatenatingMediaSource concatenatingMediaSource;
  private SimpleExoPlayer exoPlayer;
  private DefaultHttpDataSourceFactory httpDataSourceFactory;
  private DefaultDataSourceFactory rawDataSourceFactory;
  private int currentItemIndex;

  /**
   * @param context A {@link Context}.
   * @param playerView The {@link PlayerView} for local playback.
   */
  public static PlayerManager createPlayerManager(
      Context context,
      PlayerView playerView
    ) {
    PlayerManager playerManager = new PlayerManager(context, playerView);
    playerManager.init();
    return playerManager;
  }

  private PlayerManager(
      Context context,
      PlayerView playerView
    ) {
    this.playerView = playerView;
    this.mediaQueue = new ArrayList<>();
    this.concatenatingMediaSource = new ConcatenatingMediaSource();

    DefaultTrackSelector trackSelector = new DefaultTrackSelector();
    RenderersFactory renderersFactory = new DefaultRenderersFactory(context);
    this.exoPlayer = ExoPlayerFactory.newSimpleInstance(context, renderersFactory, trackSelector);
    this.exoPlayer.addListener(this);
    this.playerView.setKeepContentOnPlayerReset(false);
    this.playerView.setPlayer(this.exoPlayer);

    ExoPlayerEventLogger exoLogger = new ExoPlayerEventLogger(trackSelector);
    this.exoPlayer.addListener(exoLogger);

    String userAgent = context.getResources().getString(R.string.user_agent);
    this.httpDataSourceFactory = new DefaultHttpDataSourceFactory(userAgent);
    this.rawDataSourceFactory  = new DefaultDataSourceFactory(context, userAgent);

    this.currentItemIndex = C.INDEX_UNSET;
  }

  // Query state of player.

  /**
   * @return Is the instance of ExoPlayer able to immediately play from its current position.
   */
  public boolean isPlayerReady() {
    if (exoPlayer == null)
      return false;

    int state = exoPlayer.getPlaybackState();
    return (state == Player.STATE_READY);
  }

  /**
   * @return The duration of the current video in milliseconds, or -1 if the duration is not known.
   */
  public long getCurrentVideoDuration() {
    if (exoPlayer == null)
      return 0l;

    long durationMs = exoPlayer.getDuration();
    if (durationMs == C.TIME_UNSET) durationMs = -1l;
    return durationMs;
  }

  /**
   * @return The position of the current video in milliseconds.
   */
  public long getCurrentVideoPosition() {
    if (exoPlayer == null)
      return 0l;

    long positionMs = exoPlayer.getCurrentPosition();
    return positionMs;
  }

  // Queue manipulation methods.

  /**
   * Plays a specified queue item in the current player.
   *
   * @param itemIndex The index of the item to play.
   */
  public void selectQueueItem(int itemIndex) {
    setCurrentItem(itemIndex, true);
  }

  /**
   * @return The index of the currently played item.
   */
  public int getCurrentItemIndex() {
    return currentItemIndex;
  }

  /**
   * Appends {@link VideoSource} to the media queue.
   *
   * @param uri The URL to a video file or stream.
   * @param mimeType The mime-type of the video file or stream.
   * @param referer The URL to include in the 'Referer' HTTP header of requests to retrieve the video file or stream.
   * @param startPosition The position at which to start playback within the video file or (non-live) stream. When value < 1.0, it is interpreted to mean a percentage of the total video length. When value >= 1.0, it is interpreted to mean a fixed offset in seconds.
   */
  public void addItem(
    String uri,
    String mimeType,
    String referer,
    float startPosition
  ) {
    VideoSource sample = VideoSource.createVideoSource(uri, mimeType, referer, startPosition);
    addItem(sample);
  }

  /**
   * Appends {@code sample} to the media queue.
   *
   * @param sample The {@link VideoSource} to append.
   */
  public void addItem(VideoSource sample) {
    if ((sample.uri == null) || sample.uri.isEmpty()) return;

    mediaQueue.add(sample);
    concatenatingMediaSource.addMediaSource(buildMediaSource(sample));
  }

  /**
   * @return The size of the media queue.
   */
  public int getMediaQueueSize() {
    return mediaQueue.size();
  }

  /**
   * Returns the item at the given index in the media queue.
   *
   * @param position The index of the item.
   * @return The item at the given index in the media queue.
   */
  public VideoSource getItem(int position) {
    return (getMediaQueueSize() > position)
      ? mediaQueue.get(position)
      : null;
  }

  /**
   * Removes the item at the given index from the media queue.
   *
   * @param itemIndex The index of the item to remove.
   * @return Whether the removal was successful.
   */
  public boolean removeItem(int itemIndex) {
    concatenatingMediaSource.removeMediaSource(itemIndex);
    mediaQueue.remove(itemIndex);
    if ((itemIndex == currentItemIndex) && (itemIndex == mediaQueue.size())) {
      maybeSetCurrentItemAndNotify(C.INDEX_UNSET);
    } else if (itemIndex < currentItemIndex) {
      maybeSetCurrentItemAndNotify(currentItemIndex - 1);
    }
    return true;
  }

  /**
   * Moves an item within the queue.
   *
   * @param fromIndex The index of the item to move.
   * @param toIndex The target index of the item in the queue.
   * @return Whether the item move was successful.
   */
  public boolean moveItem(int fromIndex, int toIndex) {
    // Player update.
    concatenatingMediaSource.moveMediaSource(fromIndex, toIndex);

    mediaQueue.add(toIndex, mediaQueue.remove(fromIndex));

    // Index update.
    if (fromIndex == currentItemIndex) {
      maybeSetCurrentItemAndNotify(toIndex);
    } else if (fromIndex < currentItemIndex && toIndex >= currentItemIndex) {
      maybeSetCurrentItemAndNotify(currentItemIndex - 1);
    } else if (fromIndex > currentItemIndex && toIndex <= currentItemIndex) {
      maybeSetCurrentItemAndNotify(currentItemIndex + 1);
    }

    return true;
  }

  // AirPlay functionality (exposed by HTTP endpoints)
  //   http://nto.github.io/AirPlay.html#video

  /**
   * Clears the media queue and adds the specified {@link VideoSource}.
   *
   * @param uri The URL to a video file or stream.
   * @param startPosition The position at which to start playback within the video file or (non-live) stream. When value < 1.0, it is interpreted to mean a percentage of the total video length. When value >= 1.0, it is interpreted to mean a fixed offset in seconds.
   */
  public void AirPlay_play(String uri, float startPosition) {
    resetQueue();

    addItem(uri, null, null, startPosition);

    selectQueueItem(0);
    exoPlayer.previous();
  }

  /**
   * Seek within the current video.
   *
   * @param positionSec The position as a fixed offset in seconds.
   */
  public void AirPlay_scrub(float positionSec) {
    if (exoPlayer.isCurrentWindowSeekable()) {
      long positionMs = ((long) positionSec) * 1000;
      exoPlayer.seekTo(currentItemIndex, positionMs);
    }
  }

  /**
   * Change rate of speed for video playback.
   *
   * @param rate New rate of speed for video playback. The value 0.0 is equivalent to 'pause'.
   */
  public void AirPlay_rate(float rate) {
    if (rate == 0f) {
      // pause playback
      if (exoPlayer.isPlaying())
        exoPlayer.setPlayWhenReady(false);
    }
    else {
      // update playback speed
      exoPlayer.setPlaybackParameters(
        new PlaybackParameters(rate)
      );

      // resume playback if paused
      if (!exoPlayer.isPlaying())
        exoPlayer.setPlayWhenReady(true);
    }
  }

  /**
   * Stop video playback and reset the player.
   */
  public void AirPlay_stop() {
    resetQueue();

    addRawVideoItem(R.raw.airplay);

    selectQueueItem(0);
    exoPlayer.previous();
  }

  // extra non-standard functionality (exposed by HTTP endpoints)

  /**
   * Appends {@link VideoSource} to the media queue.
   *
   * @param uri The URL to a video file or stream.
   * @param referer The URL to include in the 'Referer' HTTP header of requests to retrieve the video file or stream.
   * @param startPosition The position at which to start playback within the video file or (non-live) stream. When value < 1.0, it is interpreted to mean a percentage of the total video length. When value >= 1.0, it is interpreted to mean a fixed offset in seconds.
   */
  public void AirPlay_queue(
    String uri,
    String referer,
    float startPosition
  ) {
    addItem(uri, null, referer, startPosition);
  }

  /**
   * Skip forward to the next {@link VideoSource} in the media queue.
   */
  public void AirPlay_next() {
    if (exoPlayer.hasNext()) {
      exoPlayer.next();
    }
  }

  /**
   * Skip backward to the previous {@link VideoSource} in the media queue.
   */
  public void AirPlay_previous() {
    if (exoPlayer.hasPrevious()) {
      exoPlayer.previous();
    }
  }

  /**
   * Change audio volume level.
   *
   * @param audioVolume New rate audio volume level. The range of acceptable values is 0.0 to 1.0. The value 0.0 is equivalent to 'mute'. The value 1.0 is unity gain.
   */
  public void AirPlay_volume(float audioVolume) {
    exoPlayer.setVolume(audioVolume); // range of values: 0.0 (mute) - 1.0 (unity gain)
  }

  // Miscellaneous methods.

  /**
   * Dispatches a given {@link KeyEvent} to the corresponding view of the current player.
   *
   * @param event The {@link KeyEvent}.
   * @return Whether the event was handled by the target view.
   */
  public boolean dispatchKeyEvent(KeyEvent event) {
    return playerView.dispatchKeyEvent(event);
  }

  /**
   * Releases the manager and instance of ExoPlayer that it holds.
   */
  public void release() {
    try {
      release_exoPlayer();

      mediaQueue.clear();
      concatenatingMediaSource.clear();

      mediaQueue = null;
      concatenatingMediaSource = null;
      httpDataSourceFactory = null;
      rawDataSourceFactory  = null;
      currentItemIndex = C.INDEX_UNSET;
    }
    catch (Exception e){}
  }

  /**
   * Releases the instance of ExoPlayer.
   */
  private void release_exoPlayer() {
    try {
      playerView.setPlayer(null);
      exoPlayer.removeListener(this);
      exoPlayer.stop(true);
      exoPlayer.release();

      playerView = null;
      exoPlayer = null;
    }
    catch (Exception e){}
  }

  // Player.EventListener implementation.

  @Override
  public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
    updateCurrentItemIndex();
  }

  @Override
  public void onPositionDiscontinuity(@DiscontinuityReason int reason) {
    updateCurrentItemIndex();
  }

  @Override
  public void onTimelineChanged(
      Timeline timeline, @Nullable Object manifest, @TimelineChangeReason int reason
  ){
    updateCurrentItemIndex();
  }

  @Override
  public void onPlayerError(ExoPlaybackException error) {
    if (error.type == ExoPlaybackException.TYPE_SOURCE) {
      exoPlayer.next();
    }
  }

  // Internal methods.

  private void init() {
    // Media queue management.
    exoPlayer.prepare(concatenatingMediaSource);
  }

  private void resetQueue() {
    mediaQueue.clear();
    concatenatingMediaSource.clear();
    currentItemIndex = C.INDEX_UNSET;
  }

  private void updateCurrentItemIndex() {
    int playbackState = exoPlayer.getPlaybackState();

    int currentItemIndex = ((playbackState != Player.STATE_IDLE) && (playbackState != Player.STATE_ENDED))
      ? exoPlayer.getCurrentWindowIndex()
      : C.INDEX_UNSET;

    maybeSetCurrentItemAndNotify(currentItemIndex);
  }

  /**
   * Starts playback of the item at the given position.
   *
   * @param itemIndex The index of the item to play.
   * @param playWhenReady Whether the player should proceed when ready to do so.
   */
  private void setCurrentItem(int itemIndex, boolean playWhenReady) {
    maybeSetCurrentItemAndNotify(itemIndex);
    exoPlayer.setPlayWhenReady(playWhenReady);
  }

  private void maybeSetCurrentItemAndNotify(int currentItemIndex) {
    if (this.currentItemIndex != currentItemIndex) {
      this.currentItemIndex = currentItemIndex;

      if (currentItemIndex != C.INDEX_UNSET) {
        seekToStartPosition(currentItemIndex);
        setHttpRequestHeaders(currentItemIndex);
      }
    }
  }

  private void seekToStartPosition(int currentItemIndex) {
    VideoSource sample = getItem(currentItemIndex);
    if (sample == null) return;

    float position = sample.startPosition;

    if ((position > 0f) && (position < 1f)) {
      // percentage
      long duration = exoPlayer.getDuration(); // ms
      if (duration != C.TIME_UNSET) {
        long positionMs = (long) (duration * position);
        exoPlayer.seekTo(currentItemIndex, positionMs);
      }
    }
    else if (position >= 1f) {
      // fixed offset in seconds
      long positionMs = ((long) position) * 1000;
      exoPlayer.seekTo(currentItemIndex, positionMs);
    }
  }

  private void setHttpRequestHeaders(int currentItemIndex) {
    VideoSource sample = getItem(currentItemIndex);
    if (sample == null) return;

    if ((sample.referer != null) && !sample.referer.isEmpty()) {
      Uri referer   = Uri.parse(sample.referer);
      String origin = referer.getScheme() + "://" + referer.getAuthority();

      setHttpRequestHeader("origin",  origin);
      setHttpRequestHeader("referer", sample.referer);
    }
  }

  private void setHttpRequestHeader(String name, String value) {
    httpDataSourceFactory.getDefaultRequestProperties().set(name, value);
  }

  private MediaSource buildMediaSource(VideoSource sample) {
    Uri uri = Uri.parse(sample.uri);

    switch (sample.mimeType) {
      case MimeTypes.APPLICATION_M3U8:
        return new HlsMediaSource.Factory(httpDataSourceFactory).createMediaSource(uri);
      case MimeTypes.APPLICATION_MPD:
        return new DashMediaSource.Factory(httpDataSourceFactory).createMediaSource(uri);
      case MimeTypes.APPLICATION_SS:
        return new SsMediaSource.Factory(httpDataSourceFactory).createMediaSource(uri);
      default:
        return new ExtractorMediaSource.Factory(httpDataSourceFactory).createMediaSource(uri);
    }
  }

  private MediaSource buildRawVideoMediaSource(int rawResourceId) {
    Uri uri = RawResourceDataSource.buildRawResourceUri(rawResourceId);

    return new ExtractorMediaSource.Factory(rawDataSourceFactory).createMediaSource(uri);
  }

  private void addRawVideoItem(int rawResourceId) {
    VideoSource sample = VideoSource.createVideoSource("raw", "raw", null, 0f);

    mediaQueue.add(sample);
    concatenatingMediaSource.addMediaSource(buildRawVideoMediaSource(rawResourceId));
  }

}