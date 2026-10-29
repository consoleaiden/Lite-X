/*
 * Sensitive word masking (Lite-X custom feature).
 *
 * Words configured by the user must never be shown as-is: every occurrence is
 * replaced with the same number of '*' characters. Because the replacement has
 * exactly the same length as the source text, all ·offset based structures
 * built on top of it (TextEntity positions, selection ranges, …) stay valid.
 *
 * Everything is stored through Settings (LevelDB) and every mutation bumps
 * `version`, so UI layers can cheaply detect "the mask changed while I was in
 * the background" and redraw.
 *
 * Performance notes:
 *  - when the feature is off or the word list is empty, {@link #filter(String)}
 *    returns the very same string instance without any allocation;
 *  - the word list is compiled once into an Aho-Corasick automaton, which scans
 *    a text in O(text length) regardless of how many words are configured;
 *  - matching uses a per-character normalization that is strictly 1:1
 *    (lower casing + full-width → half-width folding), so indexes of the
 *    normalized text always map 1:1 onto the original text.
 */
package org.thunderdog.challegram.unsorted;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SensitiveWords {
  private static final String KEY_ENABLED = "settings_sensitive_words_enabled";
  private static final String KEY_WORDS = "settings_sensitive_words";

  private static Boolean enabled;
  private static List<String> words;
  private static volatile Automaton automaton;
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

  @NonNull
  public static List<String> getWords () {
    if (words == null) {
      List<String> parsed = new ArrayList<>();
      String raw = Settings.instance().getString(KEY_WORDS, "");
      if (raw != null && !raw.isEmpty()) {
        for (String line : raw.split("\n")) {
          String word = sanitize(line);
          if (!word.isEmpty() && !parsed.contains(word))
            parsed.add(word);
        }
      }
      words = parsed;
    }
    return words;
  }

  private static void saveWords (@NonNull List<String> newWords) {
    words = new ArrayList<>(newWords);
    StringBuilder b = new StringBuilder();
    for (String word : words) {
      if (b.length() > 0)
        b.append('\n');
      b.append(sanitize(word));
    }
    Settings.instance().putString(KEY_WORDS, b.toString());
    synchronized (SensitiveWords.class) {
      automaton = null;
    }
    bumpVersion();
  }

  /** Adds a single word. Duplicated and empty words are ignored. */
  public static void addWord (@Nullable String word) {
    String normalized = sanitize(word);
    if (normalized.isEmpty())
      return;
    List<String> list = new ArrayList<>(getWords());
    if (list.contains(normalized))
      return;
    list.add(normalized);
    saveWords(list);
  }

  /**
   * Imports several words at once. The input may separate words with new lines,
   * commas (both ASCII and CJK variants), semicolons or spaces.
   *
   * @return how many words were actually added (duplicates are skipped)
   */
  public static int importWords (@Nullable CharSequence raw) {
    if (raw == null)
      return 0;
    List<String> candidates = new ArrayList<>();
    for (String token : raw.toString().split("[\n\r,，、;；\\s]+")) {
      String word = sanitize(token);
      if (!word.isEmpty() && !candidates.contains(word))
        candidates.add(word);
    }
    if (candidates.isEmpty())
      return 0;
    List<String> list = new ArrayList<>(getWords());
    int added = 0;
    for (String word : candidates) {
      if (!list.contains(word)) {
        list.add(word);
        added++;
      }
    }
    if (added == 0)
      return 0;
    saveWords(list);
    return added;
  }

  /** Replaces the word stored at {@code index}, keeping its position in the list. */
  public static void updateWord (int index, @Nullable String word) {
    String normalized = sanitize(word);
    if (normalized.isEmpty())
      return;
    List<String> list = new ArrayList<>(getWords());
    if (index < 0 || index >= list.size())
      return;
    if (list.get(index).equals(normalized))
      return;
    list.set(index, normalized);
    saveWords(list);
  }

  public static void removeWordAt (int index) {
    List<String> list = new ArrayList<>(getWords());
    if (index < 0 || index >= list.size())
      return;
    list.remove(index);
    saveWords(list);
  }

  private static String sanitize (@Nullable String word) {
    return word == null ? "" : word.replace("\n", " ").replace("\r", " ").replace("\t", " ").trim();
  }

  /**
   * Bumped every time the configuration changes, so that UI layers can tell
   * whether they need to redraw their content.
   */
  public static int version () {
    return version;
  }

  private static void bumpVersion () {
    version++;
  }

  // == Masking ==

  /**
   * Replaces every sensitive word occurrence with the same number of '*'.
   * Returns the very same instance when there is nothing to mask, which keeps
   * the common case (feature off) allocation free.
   */
  @Nullable
  public static String filter (@Nullable String text) {
    if (text == null || text.isEmpty())
      return text;
    if (!isEnabled())
      return text;
    Automaton current = automaton;
    if (current == null) {
      current = buildAutomaton();
      if (current == null)
        return text;
    }
    boolean[] masked = current.collect(normalize(text));
    if (masked == null)
      return text;
    char[] chars = text.toCharArray();
    for (int i = 0; i < chars.length; i++) {
      if (masked[i])
        chars[i] = '*';
    }
    return new String(chars);
  }

  @Nullable
  private static Automaton buildAutomaton () {
    List<String> list = getWords();
    if (list.isEmpty())
      return null;
    synchronized (SensitiveWords.class) {
      if (automaton == null)
        automaton = new Automaton(list);
      return automaton;
    }
  }

  /**
   * Lower cases and folds full-width ASCII variants to their half-width form.
   * The mapping is strictly 1 character → 1 character, which is what allows
   * {@link #filter(String)} to reuse indexes of the normalized text on the
   * original text.
   */
  private static char normalizeChar (char c) {
    if (c >= 0xFF01 && c <= 0xFF5E) // full-width "!" … "~"
      c = (char) (c - 0xFEE0);
    else if (c == 0x3000) // ideographic space
      c = ' ';
    return Character.toLowerCase(c);
  }

  @NonNull
  private static String normalize (@NonNull String text) {
    char[] chars = text.toCharArray();
    for (int i = 0; i < chars.length; i++)
      chars[i] = normalizeChar(chars[i]);
    return new String(chars);
  }

  // == Matching ==

  /**
   * Aho-Corasick automaton: all configured words are searched at once in a
   * single linear pass over the text.
   */
  private static final class Automaton {
    private final HashMap<Character, Integer>[] children;
    private final int[] fail;
    /** Length of the longest word ending at this node (including fail chain). */
    private final int[] output;

    @SuppressWarnings("unchecked")
    Automaton (@NonNull List<String> rawWords) {
      List<String> normalizedWords = new ArrayList<>(rawWords.size());
      int maxLength = 0;
      for (String word : rawWords) {
        String normalized = normalize(word);
        if (!normalized.isEmpty()) {
          normalizedWords.add(normalized);
          maxLength += normalized.length();
        }
      }
      int maxNodes = maxLength + 1;
      children = new HashMap[maxNodes];
      fail = new int[maxNodes];
      output = new int[maxNodes];

      int size = 1;
      for (String word : normalizedWords) {
        int state = 0;
        for (int i = 0; i < word.length(); i++) {
          int next = child(state, word.charAt(i));
          if (next == -1) {
            next = size++;
            if (children[state] == null)
              children[state] = new HashMap<>();
            children[state].put(word.charAt(i), next);
          }
          state = next;
        }
        if (word.length() > output[state])
          output[state] = word.length();
      }

      // Breadth-first propagation of failure links.
      int[] queue = new int[Math.max(1, size)];
      int head = 0, tail = 0;
      for (Map.Entry<Character, Integer> entry : entries(0)) {
        queue[tail++] = entry.getValue();
      }
      while (head < tail) {
        int current = queue[head++];
        if (output[fail[current]] > output[current])
          output[current] = output[fail[current]];
        for (Map.Entry<Character, Integer> entry : entries(current)) {
          char c = entry.getKey();
          int target = entry.getValue();
          int fallback = fail[current];
          while (fallback != 0 && child(fallback, c) == -1) {
            fallback = fail[fallback];
          }
          int candidate = child(fallback, c);
          fail[target] = (candidate != -1 && candidate != target) ? candidate : 0;
          queue[tail++] = target;
        }
      }
    }

    private int child (int state, char c) {
      HashMap<Character, Integer> map = children[state];
      if (map == null)
        return -1;
      Integer next = map.get(c);
      return next == null ? -1 : next;
    }

    private Iterable<Map.Entry<Character, Integer>> entries (int state) {
      HashMap<Character, Integer> map = children[state];
      return map == null ? Collections.<Map.Entry<Character, Integer>>emptyList() : map.entrySet();
    }

    /**
     * Marks every character covered by a sensitive word.
     *
     * @param normalizedText text already processed by {@link #normalize(String)}
     * @return null when nothing matched, otherwise a mask aligned 1:1 with the text
     */
    @Nullable
    boolean[] collect (@NonNull String normalizedText) {
      int state = 0;
      boolean[] masked = null;
      int length = normalizedText.length();
      for (int i = 0; i < length; i++) {
        char c = normalizedText.charAt(i);
        while (state != 0 && child(state, c) == -1)
          state = fail[state];
        int next = child(state, c);
        state = next != -1 ? next : 0;
        int wordLength = output[state];
        if (wordLength > 0) {
          if (masked == null)
            masked = new boolean[length];
          int end = i + 1;
          int start = Math.max(0, end - wordLength);
          for (int j = start; j < end; j++)
            masked[j] = true;
        }
      }
      return masked;
    }
  }
}
