/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package org.thunderdog.challegram.mediaview;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;

/**
 * Seek state and HUD rendering for horizontal swipes across the video surface.
 *
 * <p>This class deliberately does <b>not</b> consume {@link android.view.MotionEvent}s.
 * A full size overlay view cannot do the job: returning {@code false} from
 * {@code ACTION_DOWN} removes the view from the gesture entirely, so the pager below
 * would win every subsequent {@code ACTION_MOVE}; returning {@code true} would swallow
 * clicks and vertical gestures instead. The only reliable place to arbitrate is
 * {@code MediaViewController.contentView.dispatchTouchEvent()}, which still sees every
 * event after the pager, the gesture detector and the click handler have each tried to
 * claim it. That code decides, and only calls into this class once a drag is clearly
 * horizontal.</p>
 *
 * <p>So the whole interaction reduces to: a drag that is horizontal wins the gesture and
 * seeks, everything else is never intercepted and behaves exactly as before.</p>
 */
public class VideoSeekGestureController {
  public interface Delegate {
    /** Current playback position in milliseconds, {@code 0} when unknown. */
    long getPositionMs ();

    /** Total duration in milliseconds, {@code 0} when unknown. */
    long getDurationMs ();

    /** Seek is currently possible (video, loaded, not in picture-in-picture). */
    boolean canSeek ();

    /** Called continuously while the finger moves horizontally. */
    void onSeekPreview (long targetPositionMs);

    /** Called once, when the finger is released after a seek. */
    void onSeekCommit (long targetPositionMs);

    /** Called when a preview is abandoned (cancelled gesture, second finger). */
    void onSeekCancelled ();

    /** Redraw request for the overlay that owns this controller. */
    void invalidateOverlay ();
  }

  /** Fraction of the screen width that maps to the whole video duration. */
  private static final float SENSITIVITY_FACTOR = 1.0f;
  /** Travel (dp) above the system touch slop before a drag is classified. */
  public static final float START_SLOP_DP = 12f;
  /** {@code |dx| > |dy| * this} is required before a drag counts as horizontal. */
  public static final float HORIZONTAL_RATIO = 1.15f;

  private final Delegate delegate;

  private boolean seeking;
  private float anchorX;
  private long basePositionMs;
  private long durationMs;
  private long targetPositionMs;

  // Latest playback progress, mirrored here so the time label can be painted without
  // reaching back into the media cell on every frame.
  private long playbackPositionMs;
  private long playbackDurationMs;
  private boolean hasPlaybackProgress;

  private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF hudRect = new RectF();
  private final RectF badgeRect = new RectF();
  private final StringBuilder textBuilder = new StringBuilder(16);

  public VideoSeekGestureController (Delegate delegate) {
    this.delegate = delegate;
    hudPaint.setTypeface(Fonts.getRobotoRegular());
    labelPaint.setTypeface(Fonts.getRobotoRegular());
    badgePaint.setTypeface(Fonts.getRobotoRegular());
  }

  public boolean isSeeking () {
    return seeking;
  }

  public boolean canStartSeek () {
    return !seeking && delegate.canSeek();
  }

  /**
   * Starts a seek whose anchor is the point where the finger first went down.
   *
   * @return {@code false} when the duration is still unknown. The caller must then let
   *         the gesture continue to the views below instead of cancelling them: eating
   *         a drag without being able to seek would leave the user with nothing.
   */
  public boolean beginSeek (float downX, float currentX) {
    durationMs = delegate.getDurationMs();
    if (durationMs <= 0) {
      return false;
    }
    basePositionMs = Math.max(0, delegate.getPositionMs());
    anchorX = downX;
    targetPositionMs = basePositionMs;
    seeking = true;
    updateSeekTo(currentX);
    return true;
  }

  public void updateSeekTo (float x) {
    if (!seeking) {
      return;
    }
    if (durationMs <= 0) {
      durationMs = delegate.getDurationMs();
      if (durationMs <= 0) {
        return;
      }
    }
    final int width = Math.max(1, Screen.currentWidth());
    // One full screen width covers the whole video: predictable, independent of the
    // current position, and clamped to the media bounds on both ends.
    final long delta = (long) ((double) (x - anchorX) / (double) width * (double) SENSITIVITY_FACTOR * (double) durationMs);
    targetPositionMs = Math.max(0, Math.min(durationMs, basePositionMs + delta));
    delegate.onSeekPreview(targetPositionMs);
  }

  public void commitSeek () {
    if (!seeking) {
      return;
    }
    seeking = false;
    delegate.onSeekCommit(targetPositionMs);
  }

  public void cancelSeek () {
    if (!seeking) {
      return;
    }
    seeking = false;
    delegate.onSeekCancelled();
  }

  /**
   * Fed from the player callback. Repaints only when the visible second changes, so an
   * always-on time label does not force a full surface redraw on every progress tick.
   */
  public void onPlaybackProgress (long positionMs, long durationMs) {
    final long previousSecond = playbackPositionMs / 1000L;
    this.playbackPositionMs = Math.max(0, positionMs);
    this.playbackDurationMs = Math.max(0, durationMs);
    this.hasPlaybackProgress = this.playbackDurationMs > 0;
    if (seeking || this.playbackPositionMs / 1000L != previousSecond) {
      delegate.invalidateOverlay();
    }
  }

  /** Drops the cached progress, so a photo does not keep drawing the last video's time. */
  public void resetPlaybackProgress () {
    playbackPositionMs = 0;
    playbackDurationMs = 0;
    hasPlaybackProgress = false;
    seeking = false;
  }

  // == Drawing ==

  public void draw (Canvas c, int width, int height) {
    if (seeking && durationMs > 0) {
      drawSeekHud(c, width, height);
    } else if (hasPlaybackProgress) {
      drawTimeLabel(c, width, height);
    }
  }

  /** Always-on {@code current / total} chip, just above the timeline strip. */
  private void drawTimeLabel (Canvas c, int width, int height) {
    final String label = format(playbackPositionMs) + " / " + format(playbackDurationMs);

    labelPaint.setTextSize(Screen.dp(13f));
    labelPaint.setColor(0xffffffff);
    final float textWidth = labelPaint.measureText(label);
    final float paddingH = Screen.dp(9f);
    final float paddingV = Screen.dp(4f);
    final float boxWidth = textWidth + paddingH * 2f;
    final float boxHeight = Screen.dp(13f) * 1.6f + paddingV * 2f;

    final float cx = width / 2f;
    final float bottom = height - Screen.dp(12f);
    hudRect.set(cx - boxWidth / 2f, bottom - boxHeight, cx + boxWidth / 2f, bottom);

    c.drawRoundRect(hudRect, boxHeight / 2f, boxHeight / 2f, Paints.fillingPaint(0x8c000000));
    c.drawText(label, cx - textWidth / 2f, centerBaseline(labelPaint, hudRect.centerY()), labelPaint);
  }

  private void drawSeekHud (Canvas c, int width, int height) {
    final long shown = Math.max(0, Math.min(targetPositionMs, durationMs));
    final long delta = shown - basePositionMs;
    final String label = format(shown) + " / " + format(durationMs);

    hudPaint.setTextSize(Screen.dp(20f));
    hudPaint.setColor(0xffffffff);
    final float textWidth = hudPaint.measureText(label);
    final float paddingH = Screen.dp(18f);
    final float paddingV = Screen.dp(11f);
    final float boxWidth = textWidth + paddingH * 2f;
    final float boxHeight = Screen.dp(20f) * 1.6f + paddingV * 2f;

    final float cx = width / 2f;
    final float cy = height * .5f - Screen.dp(20f);
    hudRect.set(cx - boxWidth / 2f, cy - boxHeight / 2f, cx + boxWidth / 2f, cy + boxHeight / 2f);

    c.drawRoundRect(hudRect, Screen.dp(12f), Screen.dp(12f), Paints.fillingPaint(0xb4000000));
    c.drawText(label, cx - textWidth / 2f, centerBaseline(hudPaint, cy), hudPaint);

    // Thin progress strip under the label, with the elapsed part filled.
    final float stripY = hudRect.bottom + Screen.dp(8f);
    final float stripLeft = hudRect.left + Screen.dp(6f);
    final float stripRight = hudRect.right - Screen.dp(6f);
    final float stripHeight = Screen.dp(3f);
    c.drawRoundRect(stripLeft, stripY, stripRight, stripY + stripHeight, stripHeight / 2f, stripHeight / 2f, Paints.fillingPaint(0x40ffffff));
    final float progress = Math.max(0f, Math.min(1f, (float) ((double) shown / (double) durationMs)));
    final float filled = stripLeft + (stripRight - stripLeft) * progress;
    c.drawRoundRect(stripLeft, stripY, filled, stripY + stripHeight, stripHeight / 2f, stripHeight / 2f, Paints.fillingPaint(0xffffffff));

    // "+12:34" / "-00:07" badge, tells the user where the seek will land.
    if (delta != 0) {
      textBuilder.setLength(0);
      textBuilder.append(delta > 0 ? '+' : '-');
      textBuilder.append(format(Math.abs(delta)));
      badgePaint.setTextSize(Screen.dp(14f));
      badgePaint.setColor(0xffffffff);
      final String badgeText = textBuilder.toString();
      final float badgeTextWidth = badgePaint.measureText(badgeText);
      final float badgePaddingH = Screen.dp(10f);
      final float badgePaddingV = Screen.dp(5f);
      final float badgeWidth = badgeTextWidth + badgePaddingH * 2f;
      final float badgeHeight = Screen.dp(14f) * 1.6f + badgePaddingV * 2f;
      final float badgeCy = hudRect.top - Screen.dp(10f) - badgeHeight / 2f;
      badgeRect.set(cx - badgeWidth / 2f, badgeCy - badgeHeight / 2f, cx + badgeWidth / 2f, badgeCy + badgeHeight / 2f);
      c.drawRoundRect(badgeRect, badgeHeight / 2f, badgeHeight / 2f, Paints.fillingPaint(0xb4000000));
      c.drawText(badgeText, cx - badgeTextWidth / 2f, centerBaseline(badgePaint, badgeCy), badgePaint);
    }
  }

  private static String format (long ms) {
    return Strings.buildDuration(Math.round(Math.max(0, ms) / 1000.0));
  }

  private static float centerBaseline (Paint paint, float centerY) {
    final Paint.FontMetrics fm = paint.getFontMetrics();
    return centerY - (fm.ascent + fm.descent) / 2f;
  }
}
