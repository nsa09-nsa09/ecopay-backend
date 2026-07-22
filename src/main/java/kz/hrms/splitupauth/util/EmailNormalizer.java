package kz.hrms.splitupauth.util;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Pure, dependency-free email helpers: canonicalisation, structural validation, typo suggestions
 * and log masking. No DNS and no database access — see EmailValidationService for the full
 * pipeline.
 *
 * <p>Everything here is deliberately static and side-effect free so it can be reused from Bean
 * Validation constraints, services and tests alike.
 */
public final class EmailNormalizer {

  private EmailNormalizer() {}

  /** RFC 5321 caps the whole path at 254 octets; the local part at 64. */
  private static final int MAX_TOTAL_LENGTH = 254;

  private static final int MAX_LOCAL_LENGTH = 64;

  /**
   * Deliberately stricter than {@code @Email}: requires a dot-separated TLD of at least two letters
   * and forbids the punctuation patterns that {@code @Email} happily accepts (leading/trailing dots
   * and consecutive dots are handled separately for clearer error reporting).
   */
  private static final Pattern SHAPE =
      Pattern.compile(
          "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$");

  /**
   * Popular mail domains, used both as the typo-correction targets and as a fast path that skips
   * DNS entirely — these are never going away and are looked up constantly.
   */
  private static final List<String> KNOWN_DOMAINS =
      List.of(
          "gmail.com",
          "mail.ru",
          "yandex.ru",
          "yandex.kz",
          "outlook.com",
          "hotmail.com",
          "icloud.com",
          "yahoo.com",
          "bk.ru",
          "inbox.ru",
          "list.ru",
          "internet.ru",
          "proton.me",
          "protonmail.com",
          "mail.kz",
          "kaznu.kz",
          "narxoz.kz");

  /**
   * Hand-picked corrections for the mistakes we actually see. Edit distance alone is too eager (it
   * would "correct" legitimate small domains), so the explicit map runs first and the distance
   * check below only fires for near-misses on the popular list.
   */
  private static final Map<String, String> COMMON_TYPOS =
      Map.ofEntries(
          Map.entry("gmial.com", "gmail.com"),
          Map.entry("gmai.com", "gmail.com"),
          Map.entry("gmaill.com", "gmail.com"),
          Map.entry("gmail.co", "gmail.com"),
          Map.entry("gmail.cm", "gmail.com"),
          Map.entry("gmail.con", "gmail.com"),
          Map.entry("gmail.ru", "gmail.com"),
          Map.entry("gnail.com", "gmail.com"),
          Map.entry("mai.ru", "mail.ru"),
          Map.entry("mial.ru", "mail.ru"),
          Map.entry("mail.ri", "mail.ru"),
          Map.entry("maill.ru", "mail.ru"),
          Map.entry("yandx.ru", "yandex.ru"),
          Map.entry("yandex.ryu", "yandex.ru"),
          Map.entry("yandeks.ru", "yandex.ru"),
          Map.entry("yndex.ru", "yandex.ru"),
          Map.entry("yandex.com", "yandex.ru"),
          Map.entry("outlok.com", "outlook.com"),
          Map.entry("outllok.com", "outlook.com"),
          Map.entry("outlook.co", "outlook.com"),
          Map.entry("hotmial.com", "hotmail.com"),
          Map.entry("hotmai.com", "hotmail.com"),
          Map.entry("iclod.com", "icloud.com"),
          Map.entry("icoud.com", "icloud.com"),
          Map.entry("yaho.com", "yahoo.com"),
          Map.entry("yahooo.com", "yahoo.com"));

  /**
   * Canonical storage form: trimmed, lowercased, zero-width and non-breaking spaces stripped.
   * Callers must normalize before both persisting AND looking up, otherwise "User@Gmail.com" and
   * "user@gmail.com" become two accounts.
   *
   * @return {@code null} for null/blank input so "no email" stays distinguishable from "".
   */
  public static String normalize(String raw) {
    if (raw == null) {
      return null;
    }
    // Strip whitespace the user pasted in, including the non-breaking and
    // zero-width variants that survive a plain trim().
    String cleaned = raw.replaceAll("[\\s\\u00A0\\u200B-\\u200D\\uFEFF]", "");
    return cleaned.isEmpty() ? null : cleaned.toLowerCase(java.util.Locale.ROOT);
  }

  /**
   * Structural validation of an already-normalized address.
   *
   * @return {@code true} when the address is well-formed enough to be worth a DNS lookup.
   */
  public static boolean isStructurallyValid(String normalized) {
    if (normalized == null || normalized.length() > MAX_TOTAL_LENGTH) {
      return false;
    }

    int at = normalized.lastIndexOf('@');
    if (at <= 0 || at == normalized.length() - 1) {
      return false;
    }

    String local = normalized.substring(0, at);
    String domain = normalized.substring(at + 1);

    if (local.length() > MAX_LOCAL_LENGTH) {
      return false;
    }
    // A second '@' anywhere means the local part is unquoted-illegal; we do not
    // support quoted local parts on purpose.
    if (local.indexOf('@') >= 0) {
      return false;
    }
    // Obvious junk the stock @Email validator lets through.
    if (normalized.contains("..")
        || local.startsWith(".")
        || local.endsWith(".")
        || domain.startsWith(".")
        || domain.endsWith(".")
        || domain.startsWith("-")
        || domain.endsWith("-")) {
      return false;
    }

    return SHAPE.matcher(normalized).matches();
  }

  /** Domain part of a normalized address, or {@code null} if there isn't one. */
  public static String domainOf(String normalized) {
    if (normalized == null) {
      return null;
    }
    int at = normalized.lastIndexOf('@');
    return at < 0 || at == normalized.length() - 1 ? null : normalized.substring(at + 1);
  }

  /** True for the handful of mail providers that are not worth a DNS round-trip. */
  public static boolean isWellKnownDomain(String domain) {
    return domain != null && KNOWN_DOMAINS.contains(domain);
  }

  /**
   * Best guess at what the user meant, e.g. {@code user@gmial.com} → {@code user@gmail.com}.
   *
   * <p>This is advisory only — never block on it. Callers show it as "did you mean…?".
   *
   * @return the corrected full address, or {@code null} when the domain looks fine or we have no
   *     confident guess.
   */
  public static String suggestCorrection(String normalized) {
    String domain = domainOf(normalized);
    if (domain == null || isWellKnownDomain(domain)) {
      return null;
    }
    String local = normalized.substring(0, normalized.lastIndexOf('@'));

    String mapped = COMMON_TYPOS.get(domain);
    if (mapped != null) {
      return local + "@" + mapped;
    }

    // Fall back to edit distance, but only for domains that are already close to
    // a popular one. Distance 1 on a domain of 8+ chars is a typo; on a short
    // domain it is more likely a different (legitimate) provider.
    String best = null;
    int bestDistance = Integer.MAX_VALUE;
    for (String candidate : KNOWN_DOMAINS) {
      if (Math.abs(candidate.length() - domain.length()) > 1) {
        continue;
      }
      int distance = levenshtein(domain, candidate);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = candidate;
      }
    }

    if (best != null && bestDistance == 1 && domain.length() >= 8) {
      return local + "@" + best;
    }
    return null;
  }

  /**
   * Masks an address for logs: {@code alexander@gmail.com} → {@code a*******r@gmail.com}. Logs may
   * be shipped to third-party aggregators, so raw addresses must never reach them.
   */
  public static String mask(String email) {
    if (email == null || email.isBlank()) {
      return "<none>";
    }
    int at = email.lastIndexOf('@');
    if (at <= 0) {
      return "***";
    }
    String local = email.substring(0, at);
    String domain = email.substring(at);
    if (local.length() <= 2) {
      return "*".repeat(local.length()) + domain;
    }
    return local.charAt(0)
        + "*".repeat(local.length() - 2)
        + local.charAt(local.length() - 1)
        + domain;
  }

  private static int levenshtein(String a, String b) {
    int[] previous = new int[b.length() + 1];
    int[] current = new int[b.length() + 1];
    for (int j = 0; j <= b.length(); j++) {
      previous[j] = j;
    }
    for (int i = 1; i <= a.length(); i++) {
      current[0] = i;
      for (int j = 1; j <= b.length(); j++) {
        int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
        current[j] =
            Math.min(Math.min(current[j - 1] + 1, previous[j] + 1), previous[j - 1] + cost);
      }
      int[] swap = previous;
      previous = current;
      current = swap;
    }
    return previous[b.length()];
  }
}
