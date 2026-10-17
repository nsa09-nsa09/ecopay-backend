package kz.hrms.splitupauth.util;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic display-name → URL slug normalizer used for user handles.
 *
 * <ul>
 *   <li>Transliterates Cyrillic (RU + KZ) to Latin via {@link CyrillicSlug}.
 *   <li>Collapses any run of non-{@code [a-z0-9]} characters to a single dash.
 *   <li>Trims leading/trailing dashes, caps length at 30, and falls back to {@code "user"} when the
 *       result would be shorter than 3 characters.
 * </ul>
 */
public final class SlugGenerator {

  public static final int MAX_LENGTH = 30;
  public static final int MIN_LENGTH = 3;
  public static final String FALLBACK = "user";

  private static final Pattern NON_SLUG = Pattern.compile("[^a-z0-9]+");
  private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");

  private SlugGenerator() {}

  public static String normalize(String displayName) {
    if (displayName == null) {
      return FALLBACK;
    }
    String latin = CyrillicSlug.transliterate(displayName).toLowerCase(Locale.ROOT);
    String dashed = NON_SLUG.matcher(latin).replaceAll("-");
    String trimmed = EDGE_DASHES.matcher(dashed).replaceAll("");
    if (trimmed.length() > MAX_LENGTH) {
      trimmed = trimmed.substring(0, MAX_LENGTH);
      // A trailing dash after truncation looks ugly in URLs; strip it.
      trimmed = EDGE_DASHES.matcher(trimmed).replaceAll("");
    }
    if (trimmed.length() < MIN_LENGTH) {
      return FALLBACK;
    }
    return trimmed;
  }
}
