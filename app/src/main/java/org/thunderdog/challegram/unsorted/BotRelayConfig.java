/*
 * Configuration for the optional "bot relay" used to move content that is
 * otherwise hard to copy (channels with content protection, channels where the
 * current account has no posting rights, huge clones).
 *
 * Two independent credentials are supported:
 *  - BOT_TOKEN          — a bot created via @BotFather. When the bot is an
 *                         administrator of the target chat, copies can be issued
 *                         server-side through the Bot API (no traffic spent by
 *                         the phone, no limit on the number of clients).
 *  - API_ID / API_HASH  — the MTProto application credentials. They are needed
 *                         to run an external "save restricted content" service
 *                         (user session based) and to deploy the bundled
 *                         Cloudflare relay.
 *
 * Nothing here is required for the built-in bypass: the app can always download
 * media and re-upload it using the current user account.
 */
package org.thunderdog.challegram.unsorted;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class BotRelayConfig {
  /**
   * Relay deployed from cloudflare/ of this repository. Only the address is
   * prefilled — the shared secret must be entered by the user, because the
   * repository and the published APKs are public and a hardcoded secret would
   * let anyone else spend the owner's bot quota.
   */
  public static final String DEFAULT_RELAY_URL = "https://lite-x-relay.84339075.workers.dev";

  private static final String KEY_API_ID = "settings_bot_relay_api_id";
  private static final String KEY_API_HASH = "settings_bot_relay_api_hash";
  private static final String KEY_BOT_TOKEN = "settings_bot_relay_bot_token";
  private static final String KEY_BOT_USERNAME = "settings_bot_relay_bot_username";
  private static final String KEY_RELAY_URL = "settings_bot_relay_url";
  private static final String KEY_RELAY_SECRET = "settings_bot_relay_secret";
  private static final String KEY_USE_BOT = "settings_bot_relay_use_bot";
  private static final String KEY_USE_REMOTE = "settings_bot_relay_use_remote";

  private BotRelayConfig () { }

  // == BOT_TOKEN ==

  public static @NonNull String getBotToken () {
    String value = Settings.instance().getString(KEY_BOT_TOKEN, "");
    return value != null ? value.trim() : "";
  }

  public static void setBotToken (@Nullable String token) {
    Settings.instance().putString(KEY_BOT_TOKEN, token != null ? token.trim() : "");
  }

  public static boolean hasBot () {
    return !getBotToken().isEmpty();
  }

  public static @NonNull String getBotUsername () {
    String value = Settings.instance().getString(KEY_BOT_USERNAME, "");
    return value != null ? value : "";
  }

  public static void setBotUsername (@Nullable String username) {
    Settings.instance().putString(KEY_BOT_USERNAME, username != null ? username : "");
  }

  /** Token as it may be shown in the UI: never leaks the whole secret. */
  public static @NonNull String getBotTokenPreview () {
    String token = getBotToken();
    if (token.isEmpty()) {
      return "";
    }
    int separator = token.indexOf(':');
    if (separator <= 0) {
      return token.length() <= 4 ? "••••" : token.substring(0, 2) + "••••" + token.substring(token.length() - 2);
    }
    return token.substring(0, separator) + ":••••••••";
  }

  // == API_ID / API_HASH ==

  public static int getApiId () {
    return Settings.instance().getInt(KEY_API_ID, 0);
  }

  public static void setApiId (int value) {
    Settings.instance().putInt(KEY_API_ID, Math.max(0, value));
  }

  public static @NonNull String getApiHash () {
    String value = Settings.instance().getString(KEY_API_HASH, "");
    return value != null ? value.trim() : "";
  }

  public static void setApiHash (@Nullable String value) {
    Settings.instance().putString(KEY_API_HASH, value != null ? value.trim() : "");
  }

  public static boolean hasApiCredentials () {
    return getApiId() != 0 && getApiHash().length() >= 16;
  }

  // == External relay (Cloudflare Worker / any compatible service) ==

  public static @NonNull String getRelayUrl () {
    String value = Settings.instance().getString(KEY_RELAY_URL, "");
    value = value != null ? value.trim() : "";
    return value.isEmpty() ? DEFAULT_RELAY_URL : value;
  }

  public static void setRelayUrl (@Nullable String url) {
    Settings.instance().putString(KEY_RELAY_URL, url != null ? url.trim() : "");
  }

  public static boolean hasRelayUrl () {
    String url = getRelayUrl();
    if (!(url.startsWith("http://") || url.startsWith("https://"))) {
      return false;
    }
    // The address alone is not enough: the public build ships no shared secret,
    // so every call would be rejected with 401 until the user enters one.
    return !getRelaySecret().isEmpty();
  }

  public static @NonNull String getRelaySecret () {
    String value = Settings.instance().getString(KEY_RELAY_SECRET, "");
    return value != null ? value.trim() : "";
  }

  public static void setRelaySecret (@Nullable String value) {
    Settings.instance().putString(KEY_RELAY_SECRET, value != null ? value.trim() : "");
  }

  // == Switches ==

  public static boolean useBotRelay () {
    return Settings.instance().getBoolean(KEY_USE_BOT, true);
  }

  public static void setUseBotRelay (boolean value) {
    Settings.instance().putBoolean(KEY_USE_BOT, value);
  }

  public static boolean useRemoteRelay () {
    return Settings.instance().getBoolean(KEY_USE_REMOTE, false);
  }

  public static void setUseRemoteRelay (boolean value) {
    Settings.instance().putBoolean(KEY_USE_REMOTE, value);
  }

  /** True when the bot can be used for the server-side fast path. */
  public static boolean canUseBot () {
    return useBotRelay() && hasBot();
  }

  /** True when an external relay endpoint can be used. */
  public static boolean canUseRemote () {
    return useRemoteRelay() && hasRelayUrl();
  }
}
