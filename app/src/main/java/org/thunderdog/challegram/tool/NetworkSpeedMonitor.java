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
 */

package org.thunderdog.challegram.tool;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.IntRange;
import androidx.annotation.NonNull;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.telegram.TdlibManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Measures the current network throughput by polling TDLib network statistics
 * and exposes it as a 0..4 signal level (like Wi-Fi signal strength).
 * <p>
 * The level is used to render the drawer menu icon: the amount of visible
 * signal arcs reflects the current network / proxy throughput.
 */
public final class NetworkSpeedMonitor {
  public interface Delegate {
    void onNetworkLevelChanged (@IntRange(from = 0, to = 4) int level);
  }

  private static final long POLL_INTERVAL_MS = 2000L;
  private static final long KB = 1024L;

  private static volatile NetworkSpeedMonitor instance;

  public static NetworkSpeedMonitor instance () {
    if (instance == null) {
      synchronized (NetworkSpeedMonitor.class) {
        if (instance == null) {
          instance = new NetworkSpeedMonitor();
        }
      }
    }
    return instance;
  }

  private final Handler handler = new Handler(Looper.getMainLooper());
  private final List<Delegate> delegates = new ArrayList<>(2);

  private Tdlib tdlib;
  private boolean running;
  private boolean pollInProgress;
  private long lastTotalBytes;
  private long lastPollTime;
  private int currentLevel = -1;

  private final Runnable pollRunnable = new Runnable() {
    @Override
    public void run () {
      if (!running) {
        return;
      }
      poll();
      handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }
  };

  private NetworkSpeedMonitor () { }

  public void addDelegate (@NonNull Delegate delegate) {
    if (!delegates.contains(delegate)) {
      delegates.add(delegate);
    }
    startIfNeeded();
  }

  public void removeDelegate (@NonNull Delegate delegate) {
    delegates.remove(delegate);
    if (delegates.isEmpty()) {
      stop();
    }
  }

  private void startIfNeeded () {
    if (running) {
      return;
    }
    this.tdlib = TdlibManager.instance().currentNoWakeup();
    if (tdlib == null) {
      return;
    }
    running = true;
    lastTotalBytes = 0;
    lastPollTime = 0;
    currentLevel = -1;
    pollInProgress = false;
    handler.removeCallbacks(pollRunnable);
    handler.post(pollRunnable);
  }

  private void stop () {
    running = false;
    pollInProgress = false;
    handler.removeCallbacks(pollRunnable);
  }

  private void poll () {
    Tdlib current = tdlib;
    if (current == null || pollInProgress) {
      return;
    }
    pollInProgress = true;
    final long pollStartTime = System.currentTimeMillis();
    current.send(new TdApi.GetNetworkStatistics(true), (statistics, error) -> current.ui().post(() -> {
      pollInProgress = false;
      if (!running || error != null || statistics == null) {
        return;
      }
      long totalBytes = 0;
      for (TdApi.NetworkStatisticsEntry entry : statistics.entries) {
        switch (entry.getConstructor()) {
          case TdApi.NetworkStatisticsEntryFile.CONSTRUCTOR: {
            TdApi.NetworkStatisticsEntryFile fileEntry = (TdApi.NetworkStatisticsEntryFile) entry;
            totalBytes += fileEntry.sentBytes + fileEntry.receivedBytes;
            break;
          }
          case TdApi.NetworkStatisticsEntryCall.CONSTRUCTOR: {
            TdApi.NetworkStatisticsEntryCall callEntry = (TdApi.NetworkStatisticsEntryCall) entry;
            totalBytes += callEntry.sentBytes + callEntry.receivedBytes;
            break;
          }
        }
      }
      long elapsed = pollStartTime - lastPollTime;
      long delta = totalBytes - lastTotalBytes;
      boolean firstMeasurement = lastPollTime == 0;
      lastTotalBytes = totalBytes;
      lastPollTime = pollStartTime;
      if (firstMeasurement || elapsed <= 0) {
        // Two samples are required to compute throughput
        return;
      }
      updateLevel(bytesPerSecond(delta, elapsed));
    }));
  }

  private static long bytesPerSecond (long deltaBytes, long elapsedMs) {
    if (elapsedMs <= 0) {
      return 0;
    }
    return deltaBytes * 1000L / elapsedMs;
  }

  private static int levelForSpeed (long bytesPerSecond) {
    if (bytesPerSecond < 16 * KB) {
      return 0;
    }
    if (bytesPerSecond < 128 * KB) {
      return 1;
    }
    if (bytesPerSecond < 512 * KB) {
      return 2;
    }
    if (bytesPerSecond < 2 * 1024 * KB) {
      return 3;
    }
    return 4;
  }

  private void updateLevel (long bytesPerSecond) {
    int level = levelForSpeed(bytesPerSecond);
    if (level == currentLevel) {
      return;
    }
    currentLevel = level;
    for (Delegate delegate : new ArrayList<>(delegates)) {
      delegate.onNetworkLevelChanged(level);
    }
  }
}
