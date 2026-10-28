/*
 * Content filtering by keyword and regular expression.
 *
 * Rules are matched against the plain text of a message (text or media caption).
 * Two handling modes are supported:
 *  - MODE_HIDE: the message is not rendered at all
 *  - MODE_COLLAPSE: the message is rendered as a clickable placeholder
 *
 * The filter is intentionally self-contained: everything is stored through
 * Settings (LevelDB) and every mutation bumps `version`, so UI layers can
 * cheaply detect "the filter changed while I was in the background" and reload.
 */
package org.thunderdog.challegram.unsorted;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.drinkless.tdlib.TdApi;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import tgx.td.Td;

public class ContentFilter {
  public static final int MODE_HIDE = 0;
  public static final int MODE_COLLAPSE = 1;

  public static final int TYPE_KEYWORD = 0;
  public static final int TYPE_REGEX = 1;

  private static final String KEY_ENABLED = "settings_content_filter_enabled";
  private static final String KEY_MODE = "settings_content_filter_mode";
  private static final String KEY_RULES = "settings_content_filter_rules";

  private static Boolean enabled;
  private static Integer mode;
  private static List<Rule> rules;
  private static int version = 1;

  // == State ==

  public static boolean isEnabled () {
    if (enabled == null)
      enabled = Settings.instance().getBoolean(KEY_ENABLED, false);
    return enabled;
  }

  public static void setEnabled (boolean value) {
    enabled = value;
    Settings.instance().putBoolean(KEY_ENABLED, value);
    bumpVersion();
  }

  public static int getMode () {
    if (mode == null)
      mode = Settings.instance().getInt(KEY_MODE, MODE_COLLAPSE);
    return mode;
  }

  public static void setMode (int value) {
    mode = value;
    Settings.instance().putInt(KEY_MODE, value);
    bumpVersion();
  }

  @NonNull
  public static List<Rule> getRules () {
    if (rules == null) {
      List<Rule> parsed = new ArrayList<>();
      String raw = Settings.instance().getString(KEY_RULES, "");
      if (raw != null && !raw.isEmpty()) {
        for (String line : raw.split("\n")) {
          int separator = line.indexOf('\t');
          if (separator == -1)
            continue;
          int type;
          try {
            type = Integer.parseInt(line.substring(0, separator));
          } catch (NumberFormatException e) {
            continue;
          }
          String pattern = line.substring(separator + 1);
          if (!pattern.isEmpty())
            parsed.add(new Rule(type, pattern));
        }
      }
      rules = parsed;
    }
    return rules;
  }

  public static void setRules (@NonNull List<Rule> newRules) {
    rules = new ArrayList<>(newRules);
    StringBuilder b = new StringBuilder();
    for (Rule rule : getRules()) {
      if (b.length() > 0)
        b.append('\n');
      b.append(rule.type).append('\t').append(sanitize(rule.pattern));
    }
    Settings.instance().putString(KEY_RULES, b.toString());
    bumpVersion();
  }

  public static void addRule (int type, @NonNull String pattern) {
    if (pattern.trim().isEmpty())
      return;
    List<Rule> list = new ArrayList<>(getRules());
    list.add(new Rule(type, pattern));
    setRules(list);
  }

  /**
   * Replaces the pattern of an existing rule, keeping its type.
   */
  public static void updateRule (int index, @NonNull String newPattern) {
    List<Rule> list = new ArrayList<>(getRules());
    if (index < 0 || index >= list.size())
      return;
    if (newPattern.trim().isEmpty())
      return;
    Rule old = list.get(index);
    list.set(index, new Rule(old.type, newPattern));
    setRules(list);
  }

  public static void removeRuleAt (int index) {
    List<Rule> list = new ArrayList<>(getRules());
    if (index < 0 || index >= list.size())
      return;
    list.remove(index);
    setRules(list);
  }

  private static String sanitize (String pattern) {
    return pattern.replace("\n", " ").replace("\t", " ").trim();
  }

  // == Ad filtering ==

  private static final String KEY_BLOCK_ADS = "settings_content_filter_block_ads";
  private static final String KEY_AD_URLS = "settings_content_filter_ad_urls";

  private static Boolean blockAds;
  private static List<String> blockedAdUrls;
  private static Set<String> blockedAdUrlIndex;

  /** True when sponsored/proxy-sponsor ads must not be displayed. On by default. */
  public static boolean blockAds () {
    if (blockAds == null)
      blockAds = Settings.instance().getBoolean(KEY_BLOCK_ADS, true);
    return blockAds;
  }

  public static void setBlockAds (boolean value) {
    blockAds = value;
    Settings.instance().putBoolean(KEY_BLOCK_ADS, value);
    bumpVersion();
  }

  @NonNull
  public static List<String> getBlockedAdUrls () {
    if (blockedAdUrls == null) {
      parseAdUrls();
    }
    return blockedAdUrls;
  }

  private static void parseAdUrls () {
    List<String> parsed = new ArrayList<>();
    Set<String> index = new HashSet<>();
    String raw = Settings.instance().getString(KEY_AD_URLS, "");
    if (raw != null && !raw.isEmpty()) {
      for (String line : raw.split("\n")) {
        String normalized = normalizeUrl(line);
        if (!normalized.isEmpty() && index.add(normalized)) {
          parsed.add(normalized);
        }
      }
    }
    blockedAdUrls = parsed;
    blockedAdUrlIndex = index;
  }

  private static void saveAdUrls () {
    StringBuilder b = new StringBuilder();
    for (String url : getBlockedAdUrls()) {
      if (b.length() > 0)
        b.append('\n');
      b.append(url);
    }
    Settings.instance().putString(KEY_AD_URLS, b.toString());
    bumpVersion();
  }

  /** Adds a URL (or host fragment) to the ad block list. Returns false when nothing was added. */
  public static boolean blockAdUrl (@NonNull String rawUrl) {
    String normalized = normalizeUrl(rawUrl);
    if (normalized.isEmpty()) {
      return false;
    }
    if (blockedAdUrls == null) {
      parseAdUrls();
    }
    if (!blockedAdUrlIndex.add(normalized)) {
      return false;
    }
    blockedAdUrls.add(normalized);
    saveAdUrls();
    return true;
  }

  public static boolean unblockAdUrl (int index) {
    List<String> list = getBlockedAdUrls();
    if (index < 0 || index >= list.size()) {
      return false;
    }
    String removed = list.remove(index);
    blockedAdUrlIndex.remove(removed);
    saveAdUrls();
    return true;
  }

  private static String normalizeUrl (String url) {
    if (url == null)
      return "";
    String s = url.trim().toLowerCase();
    int scheme = s.indexOf("://");
    if (scheme != -1)
      s = s.substring(scheme + 3);
    if (s.startsWith("www."))
      s = s.substring(4);
    int cut = -1;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '/' || c == '?' || c == '#' || c == ' ') {
        cut = i;
        break;
      }
    }
    String host = cut != -1 ? s.substring(0, cut) : s;
    String path = cut != -1 ? s.substring(cut) : "";
    // Store host + meaningful path start, trimmed of trailing separators
    String combined = host + path;
    while (combined.endsWith("/") || combined.endsWith("?") || combined.endsWith("&"))
      combined = combined.substring(0, combined.length() - 1);
    return combined;
  }

  /**
   * Loose host-level match: a URL is blocked when its normalized form equals a
   * stored entry or starts with the entry followed by a path separator, or the
   * other way around (entry is more specific than the message URL).
   */
  public static boolean isAdUrlBlocked (@Nullable String url) {
    if (!blockAds() || url == null || url.isEmpty())
      return false;
    if (blockedAdUrls == null) {
      parseAdUrls();
    }
    if (blockedAdUrls.isEmpty())
      return false;
    String candidate = normalizeUrl(url);
    if (candidate.isEmpty())
      return false;
    for (String blocked : blockedAdUrls) {
      if (candidate.equals(blocked))
        return true;
      int cutPoint = blocked.length();
      if (candidate.startsWith(blocked) && candidate.length() > cutPoint) {
        char next = candidate.charAt(cutPoint);
        if (next == '/' || next == '?' || next == ':' || next == '#')
          return true;
      }
      if (blocked.startsWith(candidate) && blocked.length() > candidate.length()) {
        char next = blocked.charAt(candidate.length());
        if (next == '/' || next == '?' || next == ':' || next == '#')
          return true;
      }
    }
    return false;
  }

  /** True when the chat is a MTProto proxy sponsor advertisement entry. */
  public static boolean isSponsoredChat (@Nullable TdApi.Chat chat) {
    if (chat == null || chat.positions == null)
      return false;
    for (TdApi.ChatPosition position : chat.positions) {
      if (position != null && position.source != null &&
          position.source.getConstructor() == TdApi.ChatSourceMtprotoProxy.CONSTRUCTOR) {
        return true;
      }
    }
    return false;
  }

  /**
   * Removes text regions whose URL entities hit the ad block list.
   * @return a stripped copy, or null when nothing needed to change
   */
  @Nullable
  public static TdApi.FormattedText stripBlockedAds (@Nullable TdApi.FormattedText text) {
    if (text == null || text.text == null || text.text.isEmpty() || text.entities == null || text.entities.length == 0)
      return null;
    List<int[]> removeRanges = null;
    boolean[] removeEntity = new boolean[text.entities.length];
    boolean any = false;
    for (int i = 0; i < text.entities.length; i++) {
      TdApi.TextEntity entity = text.entities[i];
      if (entity.type == null)
        continue;
      String url = null;
      switch (entity.type.getConstructor()) {
        case TdApi.TextEntityTypeUrl.CONSTRUCTOR:
          if (entity.offset >= 0 && entity.offset + entity.length <= text.text.length())
            url = text.text.substring(entity.offset, entity.offset + entity.length);
          break;
        case TdApi.TextEntityTypeTextUrl.CONSTRUCTOR:
          url = ((TdApi.TextEntityTypeTextUrl) entity.type).url;
          break;
      }
      if (url != null && isAdUrlBlocked(url)) {
        removeEntity[i] = true;
        if (removeRanges == null)
          removeRanges = new ArrayList<>();
        removeRanges.add(new int[] {entity.offset, entity.offset + entity.length});
        any = true;
      }
    }
    if (!any)
      return null;
    String source = text.text;
    List<int[]> ranges = removeRanges;
    ranges.sort((a, b) -> Integer.compare(b[0], a[0])); // descending, safe removal
    StringBuilder stripped = new StringBuilder(source);
    for (int[] range : ranges) {
      int start = Math.max(0, Math.min(range[0], stripped.length()));
      int end = Math.max(0, Math.min(range[1], stripped.length()));
      if (end > start)
        stripped.delete(start, end);
    }
    String result = stripped.toString();
    List<TdApi.TextEntity> entities = new ArrayList<>();
    for (int i = 0; i < text.entities.length; i++) {
      if (removeEntity[i])
        continue;
      TdApi.TextEntity entity = text.entities[i];
      int offset = entity.offset;
      int length = entity.length;
      // Shift entity bounds for all removed ranges located before it
      for (int[] range : ranges) {
        if (range[1] <= offset) {
          offset -= range[1] - range[0];
        } else if (range[0] < offset + length) {
          int removeStart = Math.max(range[0], offset);
          int removeEnd = Math.min(range[1], offset + length);
          if (removeEnd > removeStart) {
            if (range[0] <= offset)
              offset -= removeStart - offset;
            length -= removeEnd - removeStart;
          }
        }
      }
      if (offset >= 0 && length > 0 && offset + length <= result.length()) {
        entities.add(new TdApi.TextEntity(offset, length, entity.type));
      }
    }
    TdApi.FormattedText formatted = new TdApi.FormattedText(result, entities.toArray(new TdApi.TextEntity[0]));
    return Td.equalsTo(formatted, text) ? null : formatted;
  }

  /**
   * Removes inline keyboard buttons whose URL hits the ad block list.
   * @return the filtered keyboard, or null when every button was removed
   */
  @Nullable
  public static TdApi.ReplyMarkupInlineKeyboard filterKeyboard (@NonNull TdApi.ReplyMarkupInlineKeyboard keyboard) {
    if (!blockAds() || keyboard.rows == null || keyboard.rows.length == 0)
      return keyboard;
    boolean changed = false;
    List<TdApi.InlineKeyboardButton[]> rows = new ArrayList<>();
    for (TdApi.InlineKeyboardButton[] row : keyboard.rows) {
      List<TdApi.InlineKeyboardButton> buttons = new ArrayList<>();
      if (row != null) {
        for (TdApi.InlineKeyboardButton button : row) {
          String url = null;
          if (button.type != null) {
            switch (button.type.getConstructor()) {
              case TdApi.InlineKeyboardButtonTypeUrl.CONSTRUCTOR:
                url = ((TdApi.InlineKeyboardButtonTypeUrl) button.type).url;
                break;
              case TdApi.InlineKeyboardButtonTypeLoginUrl.CONSTRUCTOR:
                url = ((TdApi.InlineKeyboardButtonTypeLoginUrl) button.type).url;
                break;
            }
          }
          if (url != null && isAdUrlBlocked(url)) {
            changed = true;
          } else {
            buttons.add(button);
          }
        }
      }
      if (!buttons.isEmpty()) {
        rows.add(buttons.toArray(new TdApi.InlineKeyboardButton[0]));
      }
    }
    if (!changed)
      return keyboard;
    if (rows.isEmpty())
      return null;
    return new TdApi.ReplyMarkupInlineKeyboard(rows.toArray(new TdApi.InlineKeyboardButton[0][]), keyboard.forceReply);
  }

  /**
   * Bumped every time the filter configuration changes, so that UI layers can
   * tell whether they need to reload their content.
   */
  public static int version () {
    return version;
  }

  private static void bumpVersion () {
    version++;
  }

  // == Matching ==

  public static boolean matches (@Nullable TdApi.Message message) {
    return message != null && matches(Td.textOrCaption(message.content));
  }

  public static boolean matches (@Nullable TdApi.FormattedText text) {
    return text != null && matches(text.text);
  }

  public static boolean matches (@Nullable CharSequence text) {
    if (text == null || text.length() == 0)
      return false;
    List<Rule> list = getRules();
    if (list.isEmpty())
      return false;
    String haystack = text.toString();
    String haystackLower = haystack.toLowerCase();
    for (Rule rule : list) {
      if (rule.matches(haystack, haystackLower))
        return true;
    }
    return false;
  }

  /** True when the message must not be rendered at all. */
  public static boolean shouldHide (@Nullable TdApi.Message message) {
    return isEnabled() && getMode() == MODE_HIDE && matches(message);
  }

  /** True when the message content must be replaced with a placeholder. */
  public static boolean shouldCollapse (@Nullable CharSequence text) {
    return isEnabled() && getMode() == MODE_COLLAPSE && matches(text);
  }

  // == No images mode ==

  private static final String KEY_NO_IMAGES = "settings_content_filter_no_images";

  private static Boolean noImagesEnabled;

  /** True when channel photos/videos start collapsed behind a summary panel. Off by default. */
  public static boolean isNoImagesEnabled () {
    if (noImagesEnabled == null)
      noImagesEnabled = Settings.instance().getBoolean(KEY_NO_IMAGES, false);
    return noImagesEnabled;
  }

  public static void setNoImagesEnabled (boolean value) {
    noImagesEnabled = value;
    Settings.instance().putBoolean(KEY_NO_IMAGES, value);
    bumpVersion();
  }

  // == Rules ==

  public static class Rule {
    public final int type;
    @NonNull public final String pattern;

    private Pattern compiled;
    private boolean broken;

    public Rule (int type, @NonNull String pattern) {
      this.type = type;
      this.pattern = pattern;
    }

    public boolean isRegex () {
      return type == TYPE_REGEX;
    }

    public boolean isBroken () {
      if (type == TYPE_REGEX && compiled == null && !broken) {
        try {
          compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
          broken = true;
        }
      }
      return broken;
    }

    public boolean matches (String haystack, String haystackLower) {
      if (type == TYPE_KEYWORD)
        return haystackLower.contains(pattern.toLowerCase());
      if (isBroken())
        return false;
      try {
        return compiled != null && compiled.matcher(haystack).find();
      } catch (Throwable t) {
        return false;
      }
    }
  }
}
