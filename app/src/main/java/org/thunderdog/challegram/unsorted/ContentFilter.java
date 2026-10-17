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

import tgx.td.Td;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
