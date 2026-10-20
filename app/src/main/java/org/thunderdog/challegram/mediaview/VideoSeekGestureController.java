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

import androidx.annotation.Nullable;

import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.Fonts;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.Strings;

import me.vkryl.core.MathUtils;

/**
 * Horizontal swipe on the video surface to seek.
 *
 * Design constraints that shaped this class:
 * <ul>
 *   <li><b>Preview only while dragging.</b> {@link Delegate#onSeekPreview} is called
 *   continuously, {@link Delegate#onSeekCommit} only once on release, so the player is
 *   not re-buffered on every touch move.</li>
 *   <li><b>Horizontal wins over vertical.</b> Direction is locked on the first move
 *   that passes the touch slop; once locked to horizontal, vertical volume/brightness
 *   handlers never see the gesture, and vice versa.</li>
 *   <li><b>Long press = speed up.</b> If the pointer stays still for
 *   {@link #LONG_PRESS_DELAY_MS}, releasing plays the rest at 2x. Moving the finger
 *   immediately cancels that and switches to normal seeking.</li>
 *   <li><b>No interference with back gestures / paging.</b> Nothing is consumed during
 *   the slop window, so the platform still gets the chance to handle edge swipes;
 *   only a real horizontal drag is intercepted.</li>
 * </ul>
 */
public class VideoSeekGestureController {
  public interface Delegate {
    /** Current playback position, 0 when unknown. */
    long getPositionMs ();

    /** Total duration, 0 when unknown. */
    long getDurationMs ();

    /** Called continuously while the finger moves horizontally. */
    void onSeekPreview (long targetPositionMs);

    /** Called once, when the finger is released. */
    void onSeekCommit (long targetPositionMs);

    /** Called when a preview is abandoned (cancelled gesture, or long-press 2x). */
    void onSeekPreviewCancelled ();

    /** Long press without moving: temporary speed up. */
    void onSpeedUpStart ();

    void onSpeedUpEnd ();

    boolean canSeek ();
  }

  /** Pixel distance that maps to one full screen width of seeking. */
  private static final float SENSITIVITY_FACTOR = 1.0f;
  /** Movement (dp) before a seek starts, on top of the system touch slop. */
  private static final float SEEK_START_SLOP_DP = 12f;
  private static final long LONG_PRESS_DELAY_MS = 450L;
  private static final long SPEED_UP_MS = 2000L;

  private enum Mode { NONE, PENDING, SEEKING, SPEED_UP }

  private final Delegate delegate;

  private Mode mode = Mode.NONE;
  private float downX, downY;
  private long downTime;
  private long basePositionMs;
  private long currentPositionMs;
  private long durationMs;

  private boolean previewVisible;
  private float previewFactor;

  private final Paint previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final RectF previewRect = new RectF();

  public VideoSeekGestureController (Delegate delegate) {
    this.delegate = delegate;
    previewPaint.setTypeface(Fonts.getRobotoRegular());
  }

  // == Touch handling ==

  /**
   * @return true when the gesture is ours and the event must not be propagated any
   *         further (the media pager, the click handler, the vertical handlers).
   */
  public boolean onTouchEvent (MotionEvent e) {
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN: {
        mode = Mode.NONE;
        downX = e.getX();
        downY = e.getY();
        downTime = System.currentTimeMillis();
        return false; // never claim the down event: clicks and let-throughs must work
      }
      case MotionEvent.ACTION_MOVE: {
        if (e.getPointerCount() > 1) {
          cancelGesture();
          return false;
        }
        final float dx = e.getX() - downX;
        final float dy = e.getY() - downY;
        switch (mode) {
          case NONE: {
            final float slop = Math.max(Screen.getTouchSlop(), Screen.dp(SEEK_START_SLOP_DP));
            if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
              if (Math.abs(dx) > Math.abs(dy) * 1.15f && delegate.canSeek()) {
                startSeek(dx);
                return true;
              }
              // Vertical (or ambiguous): hand the gesture over for good.
              mode = Mode.PENDING;
              return false;
            }
            if (System.currentTimeMillis() - downTime >= LONG_PRESS_DELAY_MS && delegate.canSeek()) {
              startSpeedUp();
              return true;
            }
            return false;
          }
          case SEEKING: {
            updateSeek(dx);
            return true;
          }
          case SPEED_UP:
          case PENDING: {
            return mode == Mode.SPEED_UP;
          }
        }
        return false;
      }
      case MotionEvent.ACTION_UP:
      case MotionEvent.ACTION_CANCEL: {
        final boolean wasSeeking = mode == Mode.SEEKING;
        final boolean wasSpeedUp = mode == Mode.SPEED_UP;
        final boolean cancelled = e.getActionMasked() == MotionEvent.ACTION_CANCEL;
        mode = Mode.NONE;
        if (wasSeeking) {
          if (cancelled) {
            delegate.onSeekPreviewCancelled();
          } else {
            delegate.onSeekCommit(currentPositionMs);
          }
          hidePreview();
          return true;
        }
        if (wasSpeedUp) {
          delegate.onSpeedUpEnd();
          return true;
        }
        return false;
      }
    }
    return false;
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
    // One full screen width covers the whole video: predictable, and independent of
    // the current position. Capped to the media length either way.
    final long delta = (long) ((double) dx / (double) width * (double) SENSITIVITY_FACTOR * (double) durationMs);
    currentPositionMs = Math.max(0, Math.min(durationMs, basePositionMs + delta));
    previewVisible = true;
    delegate.onSeekPreview(currentPositionMs);
  }

  private void startSpeedUp () {
    mode = Mode.SPEED_UP;
    delegate.onSpeedUpStart();
  }

  private void cancelGesture () {
    if (mode == Mode.SEEKING) {
      delegate.onSeekPreviewCancelled();
      hidePreview();
    } else if (mode == Mode.SPEED_UP) {
      delegate.onSpeedUpEnd();
    }
    mode = Mode.NONE;
  }

  public boolean isActive () {
    return mode == Mode.SEEKING || mode == Mode.SPEED_UP;
  }

  public boolean isSeeking () {
    return mode == Mode.SEEKING;
  }

  // == Preview overlay ==

  public void setPreviewVisible (boolean visible, boolean animated) {
    previewVisible = visible;
    previewFactor = animated ? previewFactor : (visible ? 1f : 0f);
  }

  private void hidePreview () {
    previewVisible = false;
    previewFactor = 0f;
  }

  public boolean isPreviewVisible () {
    return previewVisible && previewFactor > 0f;
  }

  public void draw (Canvas c, int width, int height) {
    if (!isPreviewVisible()) {
      return;
    }
    final long shown = Math.min(currentPositionMs, durationMs > 0 ? durationMs : currentPositionMs);
    final String text = Strings.buildDuration(Math.round(shown / 1000.0));
    final String total = durationMs > 0 ? Strings.buildDuration(Math.round(durationMs / 1000.0)) : null;
    final String label = total != null ? text + " / " + total : text;

    previewPaint.setTextSize(Screen.dp(20f));
    previewPaint.setColor(0xffffffff);
    final float textWidth = previewPaint.measureText(label);
    final float paddingH = Screen.dp(18f);
    final float paddingV = Screen.dp(10f);
    final float boxWidth = textWidth + paddingH * 2f;
    final float boxHeight = Screen.dp(20f) * 1.6f + paddingV * 2f;

    final float cx = width / 2f;
    final float cy = height / 2f - Screen.dp(40f);
    previewRect.set(cx - boxWidth / 2f, cy - boxHeight / 2f, cx + boxWidth / 2f, cy + boxHeight / 2f);

    c.save();
    c.scale(previewFactor, previewFactor, cx, cy);
    c.drawRoundRect(previewRect, Screen.dp(12f), Screen.dp(12f), Paints.fillingPaint(0xb4000000));
    final Paint.FontMetrics fm = previewPaint.getFontMetrics();
    final float baseline = cy - (fm.ascent + fm.descent) / 2f;
    c.drawText(label, cx - textWidth / 2f, baseline, previewPaint);

    // Thin progress strip under the label.
    if (durationMs > 0) {
      final float stripY = previewRect.bottom + Screen.dp(8f);
      final float stripLeft = previewRect.left + Screen.dp(6f);
      final float stripRight = previewRect.right - Screen.dp(6f);
      final float stripHeight = Screen.dp(3f);
      c.drawRoundRect(stripLeft, stripY, stripRight, stripY + stripHeight, stripHeight / 2f, stripHeight / 2f, Paints.fillingPaint(0x40ffffff));
      final float progress = MathUtils.clamp((float) ((double) shown / (double) durationMs));
      final float filled = stripLeft + (stripRight - stripLeft) * progress;
      c.drawRoundRect(stripLeft, stripY, filled, stripY + stripHeight, stripHeight / 2f, stripHeight / 2f, Paints.fillingPaint(Theme.getColor(ColorId.white)));
    }
    c.restore();
  }
}
