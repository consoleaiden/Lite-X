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
import android.view.MotionEvent;

import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;

/**
 * Touch logic for the full-size overlay that sits on top of the video surface.
 *
 * The overlay is added <em>above</em> the media pager, so it is the only view that sees
 * the touch stream: nothing below it receives events unless this class returns
 * {@code false} for the initial {@code ACTION_DOWN}. That single rule is what makes
 * clicks, vertical volume/brightness gestures and the pager swipe keep working:
 *
 * <ul>
 *   <li><b>{@code ACTION_DOWN} is never consumed.</b> Returning {@code false} stops the
 *   event from entering our touch target, so every subsequent event of that gesture
 *   continues to be dispatched by the parent as well. We only start observing, and from
 *   the next {@code ACTION_MOVE} onwards we decide whether the gesture is ours by
 *   returning {@code true}; the parent then stops delivering to the views below.</li>
 *   <li><b>Horizontal wins.</b> As soon as the finger travels
 *   {@link #SEEK_START_SLOP_DP} in a clearly horizontal direction we claim the stream
 *   (pager never gets it) and seek. Otherwise the gesture is handed over permanently to
 *   whatever is below.</li>
 *   <li><b>Preview only while dragging.</b> {@link Delegate#onSeekPreview} runs on every
 *   move, {@link Delegate#onSeekCommit} only once on release, so the player is never
 *   re-buffered mid-drag.</li>
 *   <li><b>A tap is a tap.</b> Released without travel and without long-press, the
 *   gesture is forwarded as a click via {@link Delegate#onTap()}, which restores the
 *   tap-to-pause behaviour the overlay would otherwise swallow.</li>
 *   <li><b>Long press = 2x speed.</b> Holding still for {@link #LONG_PRESS_DELAY_MS}
 *   speeds playback up; moving the finger cancels that and switches to normal seeking.</li>
 * </ul>
 *
 * Seek by dragging means the trailing {@code ACTION_UP} no longer reaches the pager, so
 * a media switch can only be started by a deliberate horizontal drag; a slow one that
 * never crossed the seek threshold is replayed to the pager with
 * {@link Delegate#onPageFling(float)}.
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
    void onSeekPreviewCancelled ();

    /** Long press without moving: temporary playback speed up. */
    void onSpeedUpStart ();

    void onSpeedUpEnd ();

    /** A short tap landed on the video surface. */
    void onTap ();

    /**
     * A horizontal drag that never turned into a seek (typically because seeking is not
     * possible yet). The delta is in pixels; positive means "next media".
     */
    void onPageFling (float deltaX);

    /** Redraw request for the overlay that owns this controller. */
    void invalidateOverlay ();
  }

  /** Fraction of the screen width that maps to the whole video duration. */
  private static final float SENSITIVITY_FACTOR = 1.0f;
  /** Travel (dp) before a gesture is classified, on top of the system touch slop. */
  private static final float SEEK_START_SLOP_DP = 12f;
  /** {@code |dx| > |dy| * this} is required before a drag counts as horizontal. */
  private static final float HORIZONTAL_RATIO = 1.15f;
  private static final long LONG_PRESS_DELAY_MS = 450L;
  /** How long the "current / total" chip stays on screen after a tap. */
  private static final long TIME_LABEL_DURATION_MS = 2500L;
  /** Travel (px) still counted as a tap instead of a tiny drag. */
  private static final float TAP_SLOP_DP = 8f;
  /** Travel (px) of a horizontal drag that is replayed to the pager. */
  private static final float PAGE_FLING_DP = 40f;

  private enum Mode { NONE, PENDING, SEEKING, SPEED_UP }

  private final Delegate delegate;

  private Mode mode = Mode.NONE;
  private float downX, downY;
  private long downTime;
  private long basePositionMs;
  private long currentPositionMs;
  private long durationMs;
  private int seekAnchor;

  /** The HUD is drawn while a seek is in progress. */
  private boolean previewVisible;

  // Latest playback progress, mirrored here so the time label can be painted without
  // reaching back into the media cell on every frame.
  private long playbackPositionMs;
  private long playbackDurationMs;

  private boolean showTimeLabel;
  private long timeLabelHideTime;

  private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint bannerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF previewRect = new RectF();
  private final RectF bannerRect = new RectF();
  private final StringBuilder textBuilder = new StringBuilder(16);

  public VideoSeekGestureController (Delegate delegate) {
    this.delegate = delegate;
    previewPaint.setTypeface(Fonts.getRobotoRegular());
    badgePaint.setTypeface(Fonts.getRobotoRegular());
    bannerPaint.setTypeface(Fonts.getRobotoRegular());
  }

  // == Touch handling ==

  /**
   * @return {@code true} when the gesture belongs to us and must not reach the media
   *         pager / click handler below.
   */
  public boolean onTouchEvent (MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        mode = Mode.NONE;
        downX = e.getX();
        downY = e.getY();
        downTime = System.currentTimeMillis();
        seekAnchor = 0;
        previewVisible = false;
        // Never claim the down event: it has to keep flowing to the pager so that a tap
        // and a vertical gesture can still be recognised by whoever wants them.
        return false;
      }
      case MotionEvent.ACTION_POINTER_DOWN: {
        // Second finger: this is a pinch, not a seek.
        abortGesture();
        return false;
      }
      case MotionEvent.ACTION_MOVE: {
        if (e.getPointerCount() > 1) {
          abortGesture();
          return false;
        }
        final float dx = e.getX() - downX;
        final float dy = e.getY() - downY;
        if (mode == Mode.SEEKING) {
          updateSeek(dx);
          delegate.invalidateOverlay();
          return true;
        }
        if (mode == Mode.SPEED_UP) {
          // Once speed-up is active the gesture stays ours until release.
          return true;
        }
        if (mode != Mode.NONE) {
          // Already handed over to the pager / vertical handlers.
          return false;
        }
        final float slop = Math.max(Screen.getTouchSlop(), Screen.dp(SEEK_START_SLOP_DP));
        if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
          if (Math.abs(dx) > Math.abs(dy) * HORIZONTAL_RATIO) {
            if (delegate.canSeek()) {
              startSeek(dx);
              delegate.invalidateOverlay();
              return true;
            }
            // Cannot seek right now (still buffering, GIF, ...): remember the drag so it
            // can be replayed to the pager on release, but let it reach the pager.
            mode = Mode.PENDING;
            seekAnchor = dx > 0 ? 1 : -1;
            return false;
          }
          // Vertical (volume / brightness) or ambiguous: hand it over for good.
          mode = Mode.PENDING;
          return false;
        }
        if (System.currentTimeMillis() - downTime >= LONG_PRESS_DELAY_MS && delegate.canSeek()) {
          mode = Mode.SPEED_UP;
          delegate.onSpeedUpStart();
          delegate.invalidateOverlay();
          return true;
        }
        return false;
      }
      case MotionEvent.ACTION_UP: {
        final Mode finished = mode;
        mode = Mode.NONE;
        if (finished == Mode.SEEKING) {
          previewVisible = false;
          delegate.onSeekCommit(currentPositionMs);
          delegate.invalidateOverlay();
          return true;
        }
        if (finished == Mode.SPEED_UP) {
          delegate.onSpeedUpEnd();
          delegate.invalidateOverlay();
          return true;
        }
        if (finished == Mode.PENDING && seekAnchor != 0 && Math.abs(e.getX() - downX) > Screen.dp(PAGE_FLING_DP)) {
          // Horizontal drag that could not become a seek: let the pager do its job.
          seekAnchor = 0;
          delegate.onPageFling(e.getX() - downX);
          return true;
        }
        seekAnchor = 0;
        tap(e);
        return false;
      }
      case MotionEvent.ACTION_CANCEL: {
        final Mode finished = mode;
        mode = Mode.NONE;
        seekAnchor = 0;
        previewVisible = false;
        if (finished == Mode.SEEKING) {
          delegate.onSeekPreviewCancelled();
        } else if (finished == Mode.SPEED_UP) {
          delegate.onSpeedUpEnd();
        }
        delegate.invalidateOverlay();
        return finished == Mode.SEEKING || finished == Mode.SPEED_UP;
      }
    }
    return false;
  }

  /**
   * A release without any travel is a tap. Because the pointer never left the overlay,
   * the views below never got the chance to handle it, so we forward it ourselves.
   */
  private void tap (MotionEvent e) {
    if (Math.abs(e.getX() - downX) > Screen.dp(TAP_SLOP_DP) || Math.abs(e.getY() - downY) > Screen.dp(TAP_SLOP_DP)) {
      return;
    }
    if (System.currentTimeMillis() - downTime >= LONG_PRESS_DELAY_MS) {
      return;
    }
    delegate.onTap();
  }

  private void abortGesture () {
    if (mode == Mode.SEEKING) {
      delegate.onSeekPreviewCancelled();
    } else if (mode == Mode.SPEED_UP) {
      delegate.onSpeedUpEnd();
    }
    mode = Mode.NONE;
    seekAnchor = 0;
    previewVisible = false;
    delegate.invalidateOverlay();
  }

  private void startSeek (float dx) {
    mode = Mode.SEEKING;
    basePositionMs = Math.max(0, delegate.getPositionMs());
    durationMs = delegate.getDurationMs();
    currentPositionMs = basePositionMs;
    updateSeek(dx);
  }

  private void updateSeek (float dx) {
    if (durationMs <= 0) {
      durationMs = delegate.getDurationMs();
      if (durationMs <= 0) {
        return;
      }
    }
    final int width = Math.max(1, Screen.currentWidth());
    // One full screen width covers the whole video: predictable and independent of the
    // current position, and clamped to the media bounds on both ends.
    final long delta = (long) ((double) dx / (double) width * (double) SENSITIVITY_FACTOR * (double) durationMs);
    currentPositionMs = Math.max(0, Math.min(durationMs, basePositionMs + delta));
    previewVisible = true;
    delegate.onSeekPreview(currentPositionMs);
  }

  public boolean isSeeking () {
    return mode == Mode.SEEKING;
  }

  /**
   * Fed from the player callback: keeps "current / total" available for both the
   * always-on label and the seek HUD.
   */
  public void onPlaybackProgress (long positionMs, long durationMs, boolean isPlaying) {
    this.playbackPositionMs = Math.max(0, positionMs);
    this.playbackDurationMs = Math.max(0, durationMs);
    if (previewVisible) {
      delegate.invalidateOverlay();
    }
  }

  /** Shows the time label for a while (called when the surface is touched). */
  public void showTimeLabel (boolean keepVisible) {
    this.showTimeLabel = true;
    this.timeLabelHideTime = keepVisible ? Long.MAX_VALUE : System.currentTimeMillis() + TIME_LABEL_DURATION_MS;
    delegate.invalidateOverlay();
  }

  public void hideTimeLabel () {
    if (showTimeLabel) {
      showTimeLabel = false;
      delegate.invalidateOverlay();
    }
  }

  public boolean shouldDrawTimeLabel () {
    if (previewVisible) {
      return false; // the seek HUD already shows both values, bigger
    }
    if (showTimeLabel && System.currentTimeMillis() >= timeLabelHideTime) {
      showTimeLabel = false;
    }
    return showTimeLabel && playbackDurationMs > 0;
  }

  // == Overlay drawing ==

  public void draw (Canvas c, int width, int height) {
    if (previewVisible && durationMs > 0) {
      drawSeekHud(c, width, height);
    } else if (mode == Mode.SPEED_UP) {
      drawSpeedUpBadge(c, width * .5f, height * .5f - Screen.dp(40f));
    } else if (shouldDrawTimeLabel()) {
      drawTimeLabel(c, width, height);
    }
  }

  /**
   * Small "current / total" chip in the top-left corner, the same place players put it.
   * Hidden automatically {@link #TIME_LABEL_DURATION_MS} after a tap.
   */
  private void drawTimeLabel (Canvas c, int width, int height) {
    final String position = Strings.buildDuration(Math.round(playbackPositionMs / 1000.0));
    final String total = Strings.buildDuration(Math.round(playbackDurationMs / 1000.0));
    final String label = position + " / " + total;

    badgePaint.setTextSize(Screen.dp(14f));
    badgePaint.setColor(0xffffffff);
    final float textWidth = badgePaint.measureText(label);
    final float paddingH = Screen.dp(10f);
    final float paddingV = Screen.dp(5f);
    final float boxWidth = textWidth + paddingH * 2f;
    final float boxHeight = Screen.dp(14f) * 1.6f + paddingV * 2f;
    final float left = Screen.dp(16f) + paddingH;
    final float top = Screen.dp(16f) + Screen.getStatusBarHeight() + paddingV;

    bannerRect.set(left - paddingH, top - paddingV, left - paddingH + boxWidth, top - paddingV + boxHeight);
    c.drawRoundRect(bannerRect, Screen.dp(8f), Screen.dp(8f), Paints.fillingPaint(0x99000000));
    c.drawText(label, left, centerBaseline(badgePaint, top - paddingV + boxHeight / 2f), badgePaint);
  }

  private void drawSeekHud (Canvas c, int width, int height) {
    final long shown = Math.max(0, Math.min(currentPositionMs, durationMs));
    final long delta = shown - basePositionMs;
    final String position = Strings.buildDuration(Math.round(shown / 1000.0));
    final String total = Strings.buildDuration(Math.round(durationMs / 1000.0));
    final String label = position + " / " + total;

    previewPaint.setTextSize(Screen.dp(20f));
    previewPaint.setColor(0xffffffff);
    final float textWidth = previewPaint.measureText(label);
    final float paddingH = Screen.dp(18f);
    final float paddingV = Screen.dp(11f);
    final float boxWidth = textWidth + paddingH * 2f;
    final float boxHeight = Screen.dp(20f) * 1.6f + paddingV * 2f;

    final float cx = width / 2f;
    final float cy = height * .5f - Screen.dp(20f);
    previewRect.set(cx - boxWidth / 2f, cy - boxHeight / 2f, cx + boxWidth / 2f, cy + boxHeight / 2f);

    c.drawRoundRect(previewRect, Screen.dp(12f), Screen.dp(12f), Paints.fillingPaint(0xb4000000));
    final float baseline = centerBaseline(previewPaint, cy);
    c.drawText(label, cx - textWidth / 2f, baseline, previewPaint);

    // Thin progress strip under the label, with the elapsed part filled.
    final float stripY = previewRect.bottom + Screen.dp(8f);
    final float stripLeft = previewRect.left + Screen.dp(6f);
    final float stripRight = previewRect.right - Screen.dp(6f);
    final float stripHeight = Screen.dp(3f);
    c.drawRoundRect(stripLeft, stripY, stripRight, stripY + stripHeight, stripHeight / 2f, stripHeight / 2f, Paints.fillingPaint(0x40ffffff));
    final float progress = Math.max(0f, Math.min(1f, (float) ((double) shown / (double) durationMs)));
    final float filled = stripLeft + (stripRight - stripLeft) * progress;
    c.drawRoundRect(stripLeft, stripY, filled, stripY + stripHeight, stripHeight / 2f, stripHeight / 2f, Paints.fillingPaint(0xffffffff));

    // "+12:34" / "-00:07" badge, tells the user where the seek will land.
    if (delta != 0) {
      textBuilder.setLength(0);
      textBuilder.append(delta > 0 ? '+' : '-');
      textBuilder.append(Strings.buildDuration(Math.round(Math.abs(delta) / 1000.0)));
      badgePaint.setTextSize(Screen.dp(14f));
      badgePaint.setColor(0xffffffff);
      final String badgeText = textBuilder.toString();
      final float badgeTextWidth = badgePaint.measureText(badgeText);
      final float badgePaddingH = Screen.dp(10f);
      final float badgePaddingV = Screen.dp(5f);
      final float badgeWidth = badgeTextWidth + badgePaddingH * 2f;
      final float badgeHeight = Screen.dp(14f) * 1.6f + badgePaddingV * 2f;
      final float badgeCx = cx;
      final float badgeCy = previewRect.top - Screen.dp(10f) - badgeHeight / 2f;
      bannerRect.set(badgeCx - badgeWidth / 2f, badgeCy - badgeHeight / 2f, badgeCx + badgeWidth / 2f, badgeCy + badgeHeight / 2f);
      c.drawRoundRect(bannerRect, badgeHeight / 2f, badgeHeight / 2f, Paints.fillingPaint(0xb4000000));
      c.drawText(badgeText, badgeCx - badgeTextWidth / 2f, centerBaseline(badgePaint, badgeCy), badgePaint);
    }
  }

  private void drawSpeedUpBadge (Canvas c, float cx, float cy) {
    final String label = "2x";
    bannerPaint.setTextSize(Screen.dp(18f));
    bannerPaint.setColor(0xffffffff);
    final float textWidth = bannerPaint.measureText(label);
    final float paddingH = Screen.dp(16f);
    final float paddingV = Screen.dp(8f);
    final float boxWidth = textWidth + paddingH * 2f;
    final float boxHeight = Screen.dp(18f) * 1.6f + paddingV * 2f;
    bannerRect.set(cx - boxWidth / 2f, cy - boxHeight / 2f, cx + boxWidth / 2f, cy + boxHeight / 2f);
    c.drawRoundRect(bannerRect, boxHeight / 2f, boxHeight / 2f, Paints.fillingPaint(0xb4000000));
    c.drawText(label, cx - textWidth / 2f, centerBaseline(bannerPaint, cy), bannerPaint);
  }

  private static float centerBaseline (Paint paint, float centerY) {
    final Paint.FontMetrics fm = paint.getFontMetrics();
    return centerY - (fm.ascent + fm.descent) / 2f;
  }
}
