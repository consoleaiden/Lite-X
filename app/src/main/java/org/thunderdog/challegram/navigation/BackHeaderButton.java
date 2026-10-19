/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * File created on 13/02/2016 at 12:53
 */
package org.thunderdog.challegram.navigation;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.IntRange;
import androidx.annotation.Nullable;

import org.thunderdog.challegram.BaseActivity;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.theme.ColorId;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.theme.ThemeDelegate;
import org.thunderdog.challegram.theme.ThemedColorAnimator;
import org.thunderdog.challegram.tool.Paints;
import org.thunderdog.challegram.tool.Screen;
import org.thunderdog.challegram.tool.UI;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BounceAnimator;
import me.vkryl.android.util.SingleViewProvider;
import me.vkryl.core.ColorUtils;
import me.vkryl.core.MathUtils;

public class BackHeaderButton extends HeaderButton implements View.OnClickListener {
  public static final int TYPE_NONE = 1;
  public static final int TYPE_MENU = 2;
  public static final int TYPE_BACK = 3;
  public static final int TYPE_CLOSE = 4;

  private boolean reverseAngle;
  private float factor;

  private HeaderView parentHeader;
  private final Paint paint;
  private final Paint wifiPaint;

  // Wi-Fi style menu icon state
  private boolean wifiStyle;
  private int signalLevel;
  private boolean dotFullStrength;
  private final float[] arcAlpha = new float[3];
  private final float[] arcAlphaTarget = new float[3];
  private boolean arcAnimationRunning;
  private final Runnable arcAnimationRunnable = new Runnable() {
    @Override
    public void run () {
      boolean needMore = false;
      for (int i = 0; i < arcAlpha.length; i++) {
        float diff = arcAlphaTarget[i] - arcAlpha[i];
        if (Math.abs(diff) > 0.01f) {
          arcAlpha[i] += diff * 0.3f;
          needMore = true;
        } else {
          arcAlpha[i] = arcAlphaTarget[i];
        }
      }
      invalidate();
      if (needMore) {
        postOnAnimation(arcAnimationRunnable);
      } else {
        arcAnimationRunning = false;
      }
    }
  };

  public BackHeaderButton (Context context) {
    super(context);

    paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    paint.setStrokeWidth(Screen.dp(2f));
    paint.setColor(Theme.headerBackColor());

    wifiPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    wifiPaint.setStyle(Paint.Style.STROKE);
    wifiPaint.setStrokeWidth(Screen.dp(1.7f));
    wifiPaint.setStrokeCap(Paint.Cap.ROUND);
    wifiPaint.setColor(Theme.headerBackColor());
  }

  private int color;

  public void setColor (int color) {
    if (this.color != color) {
      this.color = color;
      paint.setColor(color);
      wifiPaint.setColor(color);
      invalidate();
    }
  }

  @Override
  public boolean onTouchEvent (MotionEvent e) {
    if (parentHeader != null && e.getAction() == MotionEvent.ACTION_DOWN) {
      parentHeader.onBackTouchDown(e);
    }
    return super.onTouchEvent(e);
  }

  public int getColor () {
    return color;
  }

  public void setParentHeader (HeaderView parent) {
    this.parentHeader = parent;
  }

  public void setIsReverse (boolean isReverse) {
    this.reverseAngle = isReverse;
  }

  public float getFactor () {
    return factor;
  }

  public void setFactor (float factor) {
    if (this.factor != factor) {
      this.factor = factor;
      invalidate();
    }
  }

  public static float toButtonFactor (int type) {
    switch (type) {
      case BackHeaderButton.TYPE_NONE:
      case BackHeaderButton.TYPE_MENU:
        return 0f;
      case BackHeaderButton.TYPE_BACK:
        return 1f;
      case BackHeaderButton.TYPE_CLOSE:
        return 2f;
    }
    return 0f;
  }

  public void setButtonFactor (int type) {
    if (type != BackHeaderButton.TYPE_NONE) {
      setFactor(toButtonFactor(type));
    }
    setWifiStyle(type == BackHeaderButton.TYPE_MENU);
  }

  /**
   * Sets whether the button should be rendered as a Wi-Fi style menu icon
   * instead of the classic hamburger.
   */
  public void setWifiStyle (boolean wifiStyle) {
    if (this.wifiStyle != wifiStyle) {
      this.wifiStyle = wifiStyle;
      invalidate();
    }
  }

  /**
   * Updates the amount of visible signal arcs of the Wi-Fi style menu icon.
   *
   * @param level Network quality level from {@code 0} (no signal) to {@code 4} (best signal).
   */
  public void setSignalLevel (@IntRange(from = 0, to = 4) int level) {
    level = MathUtils.clamp(level, 0, 4);
    if (this.signalLevel != level) {
      this.signalLevel = level;
      this.dotFullStrength = level >= 4;
      for (int i = 0; i < arcAlphaTarget.length; i++) {
        arcAlphaTarget[i] = level > i ? 1f : 0f;
      }
      if (!arcAnimationRunning) {
        arcAnimationRunning = true;
        postOnAnimation(arcAnimationRunnable);
      }
      invalidate();
    }
  }

  @Override
  public void onClick (View view) {
    if (parentHeader != null) {
      if (parentHeader.inSelectMode()) {
        parentHeader.closeSelectMode(true, true);
        return;
      }
      if (parentHeader.inSearchMode()) {
        parentHeader.closeSearchMode(true, null);
        return;
      }
    }
    BaseActivity context = UI.getContext(getContext());
    if (context != null) {
      if (this.factor == 0f) { // Menu button
        if (context.getDrawer() != null && !context.getDrawer().isVisible()) {
          context.getDrawer().open();
        }
      } else {
        context.performBackPress(true);
      }
    }
  }

  private final ThemedColorAnimator menuHintBadgeColor = new ThemedColorAnimator(this, AnimatorUtils.DECELERATE_INTERPOLATOR, 180l, ColorId.headerBadgeFailed);
  private final BounceAnimator menuHintBadgeVisible = new BounceAnimator(new SingleViewProvider(this));

  public void hideMenuBadge (boolean animated) {
    setMenuBadge(ColorId.NONE, null, animated);
  }

  public void setMenuBadge (@ColorId int colorId, boolean animated) {
    setMenuBadge(colorId, null, animated);
  }

  public void setMenuBadge (@ColorId int colorId, @Nullable ThemeDelegate forcedTheme, boolean animated) {
    animated = animated && (factor >= 0f && factor < 1f);
    boolean needShow = colorId != ColorId.NONE;
    if (needShow) {
      menuHintBadgeColor.setValue(colorId, forcedTheme, animated && menuHintBadgeVisible.getFloatValue() > 0f);
    }
    menuHintBadgeVisible.setValue(needShow, animated);
  }

  @Override
  public void onDraw (Canvas c) {
    super.onDraw(c);

    boolean reverseAngle = Lang.rtl() != this.reverseAngle;

    c.save();
    c.translate(getPaddingLeft() + getMeasuredWidth() / 2, getMeasuredHeight() / 2);
    float scale = 1f - Math.min(factor, 1f) * .12f;
    c.scale(scale, scale);

    // Wi-Fi style menu icon, cross-faded with the classic hamburger while morphing to back/close button
    final float wifiAlpha = wifiStyle ? 1f - MathUtils.clamp(factor / .35f) : 0f;
    if (wifiAlpha > 0f) {
      drawWifiIcon(c, wifiAlpha);
    }

    if (!wifiStyle || factor > 0f) {
      int oldAlpha = paint.getAlpha();
      if (wifiStyle) {
        paint.setAlpha((int) (255f * MathUtils.clamp(factor / .35f)));
      }
      drawClassicIcon(c, reverseAngle);
      if (wifiStyle) {
        paint.setAlpha(oldAlpha);
      }
    }

    c.restore();
  }

  // Wi-Fi style icon geometry (in dp)
  private static final float WIFI_DOT_Y = 7.6f;
  private static final float WIFI_DOT_RADIUS = 2.1f;
  private static final float WIFI_DOT_RADIUS_FULL = 2.5f;
  private static final float[] WIFI_ARC_RADII = {4.6f, 7.4f, 10.2f};
  private static final float WIFI_ARC_SWEEP = 100f; // total sweep angle in degrees
  private final RectF wifiArcRect = new RectF();
  private final Paint wifiDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);

  private void drawWifiIcon (Canvas c, float alpha) {
    final float dotY = Screen.dp(WIFI_DOT_Y);

    // Signal arcs: the amount of visible arcs reflects the current network throughput
    for (int i = 0; i < WIFI_ARC_RADII.length; i++) {
      float arcVisibility = arcAlpha[i] * alpha;
      if (arcVisibility <= 0f) {
        continue;
      }
      float radius = Screen.dp(WIFI_ARC_RADII[i]);
      wifiArcRect.set(-radius, dotY - radius, radius, dotY + radius);
      wifiPaint.setAlpha((int) (255f * arcVisibility));
      c.drawArc(wifiArcRect, -90f - WIFI_ARC_SWEEP / 2f, WIFI_ARC_SWEEP, false, wifiPaint);
    }

    // Bottom dot: orange when the menu has unread messages, themed color otherwise
    float badgeVisibility = MathUtils.clamp(1f - factor) * menuHintBadgeVisible.getFloatValue();
    float dotRadius = Screen.dp(dotFullStrength ? WIFI_DOT_RADIUS_FULL : WIFI_DOT_RADIUS) * alpha;
    if (dotRadius <= 0f) {
      return;
    }
    if (badgeVisibility > 0f) {
      int cutoutColor = parentHeader != null ? parentHeader.getFilling().getColor() : Theme.fillingColor();
      int badgeColor = ColorUtils.alphaColor(MathUtils.clamp(badgeVisibility) * alpha, menuHintBadgeColor.getIntValue());
      wifiDotPaint.setColor(cutoutColor);
      c.drawCircle(0, dotY, Screen.dp(4.5f) * badgeVisibility * alpha, wifiDotPaint);
      wifiDotPaint.setColor(badgeColor);
      c.drawCircle(0, dotY, dotRadius * badgeVisibility, wifiDotPaint);
    } else {
      wifiDotPaint.setColor(paint.getColor());
      c.drawCircle(0, dotY, dotRadius, wifiDotPaint);
    }
  }

  private void drawClassicIcon (Canvas c, boolean reverseAngle) {
    int _9 = Screen.dp(9f);
    int _85 = Screen.dp(8.5f);
    int _5 = Screen.dp(5f);
    int _35 = Screen.dp(3.5f);
    int _05 = Screen.dp(0.5f);
    int _1 = Screen.dp(1f);

    if (factor <= 1f) {
      c.rotate(factor * (reverseAngle ? -180f : 180f));
      c.drawLine(-_9, 0, _9 - _1 * factor, 0, paint);

      final float startY = _5 + _35 * Math.abs(factor);
      final float endY = _5 * (1 - Math.abs(factor)) - _05 * Math.abs(factor);

      float startX, endX;
      if (Lang.rtl()) {
        startX = _9 - _85 * Math.abs(factor);
        endX = -_9 + _05 * Math.abs(factor);
      } else {
        startX = -_9 + _85 * Math.abs(factor);
        endX = _9 - _05 * Math.abs(factor);
      }

      c.drawLine(startX, -startY, endX, -endY, paint);
      c.drawLine(startX, startY, endX, endY, paint);

      float badgeCenterX = endX;
      float badgeCenterY = -endY - Screen.dp(1.5f);
      float badgeVisibility = MathUtils.clamp(1f - factor) * menuHintBadgeVisible.getFloatValue();
      if (badgeVisibility > 0f) {
        int cutoutColor = parentHeader.getFilling().getColor();
        int badgeColor = ColorUtils.alphaColor(MathUtils.clamp(badgeVisibility), menuHintBadgeColor.getIntValue());
        c.drawCircle(badgeCenterX, badgeCenterY, Screen.dp(4.5f) * badgeVisibility, Paints.fillingPaint(cutoutColor));
        c.drawCircle(badgeCenterX, badgeCenterY, Screen.dp(3f) * badgeVisibility, Paints.fillingPaint(badgeColor));
      }
    } else {
      float factor = this.factor - 1f;

      c.rotate((reverseAngle ? 180 + (factor * -135f) : 180f + (factor * 135f)) * (Lang.rtl() ? -1 : 1));

      c.drawLine(-_9, 0, _9 - _1, 0, paint);

      float xDiff = (_9 - _05 + _9 - _85) * factor;

      if (Lang.rtl()) {
        float startX, endX;
        startX = _9 - _85;
        endX = -_9 + _05;

        final float startY = _5 + _35;
        final float endY = -_05;

        c.drawLine(startX, -startY, endX + xDiff, -endY, paint);
        c.drawLine(startX, startY, endX + xDiff, endY, paint);
      } else {
        c.drawLine(-_9 + _85, -_5 - _35, _9 - _05 - xDiff, _05, paint);
        c.drawLine(-_9 + _85, _5 + _35,  _9 - _05 - xDiff, -_05, paint);
      }
    }
  }
}
