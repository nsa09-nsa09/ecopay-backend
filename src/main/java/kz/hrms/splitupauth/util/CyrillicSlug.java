package kz.hrms.splitupauth.util;

import java.util.HashMap;
import java.util.Map;

/**
 * Transliterates Russian and Kazakh Cyrillic characters into their Latin equivalents. Unknown
 * characters are left untouched — downstream slug normalization strips anything outside {@code
 * [a-z0-9]}.
 */
public final class CyrillicSlug {

  private static final Map<Character, String> MAP = buildMap();

  private CyrillicSlug() {}

  /** Best-effort character-by-character Cyrillic → Latin transliteration. */
  public static String transliterate(String input) {
    if (input == null || input.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(input.length());
    for (int i = 0; i < input.length(); i++) {
      char c = input.charAt(i);
      String mapped = MAP.get(c);
      if (mapped != null) {
        out.append(mapped);
      } else {
        out.append(c);
      }
    }
    return out.toString();
  }

  private static Map<Character, String> buildMap() {
    Map<Character, String> m = new HashMap<>();
    // Russian (lowercase).
    putPair(m, 'а', "a");
    putPair(m, 'б', "b");
    putPair(m, 'в', "v");
    putPair(m, 'г', "g");
    putPair(m, 'д', "d");
    putPair(m, 'е', "e");
    putPair(m, 'ё', "yo");
    putPair(m, 'ж', "zh");
    putPair(m, 'з', "z");
    putPair(m, 'и', "i");
    putPair(m, 'й', "i");
    putPair(m, 'к', "k");
    putPair(m, 'л', "l");
    putPair(m, 'м', "m");
    putPair(m, 'н', "n");
    putPair(m, 'о', "o");
    putPair(m, 'п', "p");
    putPair(m, 'р', "r");
    putPair(m, 'с', "s");
    putPair(m, 'т', "t");
    putPair(m, 'у', "u");
    putPair(m, 'ф', "f");
    putPair(m, 'х', "kh");
    putPair(m, 'ц', "ts");
    putPair(m, 'ч', "ch");
    putPair(m, 'ш', "sh");
    putPair(m, 'щ', "sch");
    putPair(m, 'ъ', "");
    putPair(m, 'ы', "y");
    putPair(m, 'ь', "");
    putPair(m, 'э', "e");
    putPair(m, 'ю', "yu");
    putPair(m, 'я', "ya");
    // Kazakh-specific letters.
    putPair(m, 'ә', "a");
    putPair(m, 'ғ', "g");
    putPair(m, 'қ', "q");
    putPair(m, 'ң', "ng");
    putPair(m, 'ө', "o");
    putPair(m, 'ұ', "u");
    putPair(m, 'ү', "u");
    putPair(m, 'һ', "h");
    putPair(m, 'і', "i");
    return m;
  }

  /** Register both the lowercase char and its uppercase counterpart in one call. */
  private static void putPair(Map<Character, String> m, char lower, String replacement) {
    m.put(lower, replacement);
    char upper = Character.toUpperCase(lower);
    if (upper != lower) {
      m.put(upper, replacement);
    }
  }
}
