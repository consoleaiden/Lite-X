/*
 * This file is a part of Telegram X
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.thunderdog.challegram.tool;

import android.net.TrafficStats;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.AnyThread;
import androidx.annotation.MainThread;

import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight sampler that turns the app's own traffic (TDLib / proxy included)
 * into a 0..3 "signal strength" level used by the drawer WiFi icon.
 *
 * Level 0 = idle (all arcs hollow), 3 = fast (all arcs filled).
 */
public final class NetworkSpeedMonitor {
  public interface LevelListener {
    @MainThread
    void onNetworkLevelChanged (int level);
  }

  private static final NetworkSpeedMonitor INSTANCE = new NetworkSpeedMonitor();

  public static NetworkSpeedMonitor instance () {
    return INSTANCE;
  }

  private static final long SAMPLE_INTERVAL_MS = 1200L;
  private static final long[] THRESHOLDS = { // bytes per second
    24L * 1024L,
    256L * 1024L,
    2L * 1024L * 1024L
  };
  private static final float DECAY_FACTOR = .55f;

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<LevelListener> listeners = new ArrayList<>();

  private long lastRxBytes, lastTxBytes, lastSampleTime;
  private double smoothedSpeed;
  private int level;
  private boolean sampling;

  private final Runnable sampler = new Runnable() {
    @Override
    public void run () {
      if (!sampling) {
        return;
      }
      sample();
      handler.postDelayed(this, SAMPLE_INTERVAL_MS);
    }
  };

  private NetworkSpeedMonitor () { }

  @MainThread
  public void addListener (LevelListener listener) {
    if (listener == null || listeners.contains(listener)) {
      return;
    }
    listeners.add(listener);
    listener.onNetworkLevelChanged(level);
    if (!sampling) {
      start();
    }
  }

  @MainThread
  public void removeListener (LevelListener listener) {
    if (listener == null) {
      return;
    }
    listeners.remove(listener);
    if (listeners.isEmpty()) {
      stop();
    }
  }

  @MainThread
  public int getLevel () {
    return level;
  }

  private void start () {
    sampling = true;
    lastRxBytes = totalRxBytes();
    lastTxBytes = totalTxBytes();
    lastSampleTime = SystemClock.elapsedRealtime();
    handler.removeCallbacks(sampler);
    handler.postDelayed(sampler, SAMPLE_INTERVAL_MS);
  }

  private void stop () {
    sampling = false;
    handler.removeCallbacks(sampler);
    smoothedSpeed = 0;
    setLevel(0);
  }

  private void sample () {
    long now = SystemClock.elapsedRealtime();
    long rx = totalRxBytes();
    long tx = totalTxBytes();
    long elapsed = Math.max(1L, now - lastSampleTime);

    long deltaRx = rx >= lastRxBytes ? rx - lastRxBytes : 0;
    long deltaTx = tx >= lastTxBytes ? tx - lastTxBytes : 0;
    double speed = ((double) (deltaRx + deltaTx)) * 1000d / (double) elapsed;

    // Fast rise, smooth decay: keeps the icon from flickering between levels
    smoothedSpeed = Math.max(speed, smoothedSpeed * DECAY_FACTOR);

    lastRxBytes = rx;
    lastTxBytes = tx;
    lastSampleTime = now;

    int newLevel = 0;
    while (newLevel < THRESHOLDS.length && smoothedSpeed >= THRESHOLDS[newLevel]) {
      newLevel++;
    }
    setLevel(newLevel);
  }

  private void setLevel (int newLevel) {
    if (this.level != newLevel) {
      this.level = newLevel;
      for (int i = listeners.size() - 1; i >= 0; i--) {
        listeners.get(i).onNetworkLevelChanged(newLevel);
      }
    }
  }

  @AnyThread
  private static long totalRxBytes () {
    long value = TrafficStats.getUidRxBytes(Process.myUid());
    if (value == TrafficStats.UNSUPPORTED) {
      value = TrafficStats.getTotalRxBytes();
    }
    return value == TrafficStats.UNSUPPORTED ? 0 : value;
  }

  @AnyThread
  private static long totalTxBytes () {
    long value = TrafficStats.getUidTxBytes(Process.myUid());
    if (value == TrafficStats.UNSUPPORTED) {
      value = TrafficStats.getTotalTxBytes();
    }
    return value == TrafficStats.UNSUPPORTED ? 0 : value;
  }
}
