/*
 * Client for the bundled Cloudflare relay (see cloudflare/worker.js).
 *
 * The relay keeps BOT_TOKEN on the server side, so it can be used from networks
 * where api.telegram.org is unreachable: the phone only needs to reach the
 * Cloudflare endpoint. It performs the very same server-side "copy messages"
 * operation as {@link BotApiClient}, but batches a whole page in one request.
 */
package org.thunderdog.challegram.telegram;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.unsorted.BotRelayConfig;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class RemoteRelayClient {
  private static final int CONNECT_TIMEOUT_MS = 20_000;
  private static final int READ_TIMEOUT_MS = 120_000;

  public static final int ERROR_NOT_CONFIGURED = -1;

  public static class Result {
    public final int copied;
    public final long[] failedMessageIds;
    public final @Nullable String error;

    Result (int copied, long[] failedMessageIds, @Nullable String error) {
      this.copied = copied;
      this.failedMessageIds = failedMessageIds;
      this.error = error;
    }
  }

  public interface Callback {
    void onResult (@NonNull Result result);
  }

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "relay-client");
    thread.setDaemon(true);
    return thread;
  });

  private RemoteRelayClient () { }

  public static boolean isConfigured () {
    return BotRelayConfig.canUseRemote();
  }

  private static String endpoint (String path) {
    String base = BotRelayConfig.getRelayUrl();
    while (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return base + path;
  }

  /** Copies the given messages server-side; failures are reported per message id. */
  public static void copyMessages (long toChatId, long fromChatId, List<Long> messageIds, Callback callback) {
    if (!isConfigured()) {
      if (callback != null) {
        callback.onResult(new Result(0, new long[0], "relay is not configured"));
      }
      return;
    }
    JSONObject body = new JSONObject();
    try {
      body.put("from", String.valueOf(fromChatId));
      body.put("to", String.valueOf(toChatId));
      JSONArray ids = new JSONArray();
      for (Long id : messageIds) {
        ids.put(id != null ? id.longValue() : 0L);
      }
      body.put("messages", ids);
    } catch (JSONException ignored) { }
    post(endpoint("/api/copy"), body, callback);
  }

  public static void health (Callback callback) {
    post(endpoint("/api/health"), new JSONObject(), callback);
  }

  private static void post (String url, JSONObject body, Callback callback) {
    final String secret = BotRelayConfig.getRelaySecret();
    final String payload = body.toString();
    EXECUTOR.execute(() -> {
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        if (!secret.isEmpty()) {
          connection.setRequestProperty("X-Relay-Secret", secret);
        }
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(bytes);
        }
        int code = connection.getResponseCode();
        String raw = readFully(code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream());
        Result result = parse(raw, code);
        deliver(callback, result);
      } catch (IOException e) {
        deliver(callback, new Result(0, new long[0], e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
      }
    });
  }

  private static Result parse (@Nullable String raw, int httpCode) {
    if (raw == null || raw.isEmpty()) {
      return new Result(0, new long[0], "HTTP " + httpCode);
    }
    try {
      JSONObject json = new JSONObject(raw);
      boolean ok = json.optBoolean("ok", false);
      int copied = json.optInt("copied", 0);
      JSONArray failed = json.optJSONArray("failed");
      long[] failedIds = new long[failed != null ? failed.length() : 0];
      for (int i = 0; i < failedIds.length; i++) {
        JSONObject entry = failed.optJSONObject(i);
        failedIds[i] = entry != null ? entry.optLong("id", 0) : 0;
      }
      if (!ok) {
        return new Result(copied, failedIds, json.optString("error", "HTTP " + httpCode));
      }
      return new Result(copied, failedIds, null);
    } catch (JSONException e) {
      return new Result(0, new long[0], "malformed response");
    }
  }

  private static void deliver (@Nullable Callback callback, Result result) {
    if (callback != null) {
      UI.post(() -> callback.onResult(result));
    }
  }

  private static @Nullable String readFully (@Nullable InputStream stream) throws IOException {
    if (stream == null) {
      return null;
    }
    try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int read;
      while ((read = in.read(buffer)) != -1) {
        out.write(buffer, 0, read);
      }
      return out.toString("UTF-8");
    }
  }
}
