/*
 * Minimal Telegram Bot API client.
 *
 * Only JSON methods are supported (no multipart uploads): the relay never sends
 * files from the phone, it asks Telegram to copy an existing message into a
 * target chat. That keeps the call cheap and lets the server do all the heavy
 * lifting, which is exactly what "Auto-Forward"-style bots rely on.
 *
 * The bot must be able to see the source chat and post to the target chat,
 * otherwise Telegram answers with a regular error and the caller falls back to
 * the local "download + re-upload" path.
 */
package org.thunderdog.challegram.telegram;

import androidx.annotation.Nullable;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BotApiClient {
  private static final String API_ROOT = "https://api.telegram.org/bot";
  private static final int CONNECT_TIMEOUT_MS = 20_000;
  private static final int READ_TIMEOUT_MS = 60_000;

  public static final int ERROR_NOT_CONFIGURED = -1;

  public interface Callback {
    /**
     * @param result parsed "result" object, or null on failure
     * @param errorCode Telegram error code (or {@link #ERROR_NOT_CONFIGURED}),
     *                  0 when the call succeeded
     * @param errorMessage human readable description, null when the call succeeded
     */
    void onResult (@Nullable JSONObject result, int errorCode, @Nullable String errorMessage);
  }

  private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "bot-api-client");
    thread.setDaemon(true);
    return thread;
  });

  private BotApiClient () { }

  public static boolean isConfigured () {
    return BotRelayConfig.hasBot();
  }

  // == Public methods ==

  public static void getMe (Callback callback) {
    call("getMe", new JSONObject(), callback);
  }

  public static void getChat (long chatId, Callback callback) {
    JSONObject args = new JSONObject();
    try {
      args.put("chat_id", String.valueOf(chatId));
    } catch (JSONException ignored) { }
    call("getChat", args, callback);
  }

  public static void copyMessage (long toChatId, long fromChatId, long messageId, Callback callback) {
    JSONObject args = new JSONObject();
    try {
      args.put("chat_id", String.valueOf(toChatId));
      args.put("from_chat_id", String.valueOf(fromChatId));
      args.put("message_id", messageId);
      args.put("disable_notification", true);
    } catch (JSONException ignored) { }
    call("copyMessage", args, callback);
  }

  public static void sendMessage (long chatId, String text, Callback callback) {
    JSONObject args = new JSONObject();
    try {
      args.put("chat_id", String.valueOf(chatId));
      args.put("text", text != null ? text : "");
      args.put("disable_web_page_preview", true);
    } catch (JSONException ignored) { }
    call("sendMessage", args, callback);
  }

  /**
   * Issues a JSON request to the Bot API.
   * The callback is always invoked on the main thread.
   */
  public static void call (String method, JSONObject args, Callback callback) {
    final String token = BotRelayConfig.getBotToken();
    if (token.isEmpty()) {
      deliver(callback, null, ERROR_NOT_CONFIGURED, "BOT_TOKEN is not configured");
      return;
    }
    final String body = args != null ? args.toString() : "{}";
    EXECUTOR.execute(() -> {
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(API_ROOT + token + "/" + method).openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("Accept", "application/json");
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(payload.length);
        try (OutputStream out = connection.getOutputStream()) {
          out.write(payload);
        }
        int responseCode = connection.getResponseCode();
        InputStream stream = responseCode >= 200 && responseCode < 300 ? connection.getInputStream() : connection.getErrorStream();
        String raw = readFully(stream);
        JSONObject json = raw != null && !raw.isEmpty() ? new JSONObject(raw) : null;
        if (json != null && json.optBoolean("ok", false)) {
          deliver(callback, json.optJSONObject("result"), 0, null);
        } else if (json != null) {
          deliver(callback, null, json.optInt("error_code", responseCode), json.optString("description", "HTTP " + responseCode));
        } else {
          deliver(callback, null, responseCode, "HTTP " + responseCode);
        }
      } catch (IOException | JSONException e) {
        deliver(callback, null, 0, e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
      }
    });
  }

  private static void deliver (Callback callback, @Nullable JSONObject result, int errorCode, @Nullable String errorMessage) {
    if (callback == null) {
      return;
    }
    UI.post(() -> callback.onResult(result, errorCode, errorMessage));
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

  /** True when the error means "the server refuses to copy protected content". */
  public static boolean isProtectedContentError (@Nullable String description) {
    if (description == null) {
      return false;
    }
    String lower = description.toLowerCase();
    return lower.contains("protected content") || lower.contains("can't be forwarded") || lower.contains("chat_forwards_restricted");
  }
}
