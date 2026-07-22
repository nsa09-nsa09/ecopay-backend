package kz.hrms.splitupauth.service;

import java.util.Locale;

/**
 * The three languages the product ships in. Used only to pick email copy — no business rule ever
 * branches on locale (see CLAUDE.md: "Backend: no locale-specific business logic").
 */
public enum MailLocale {
  RU,
  KK,
  EN;

  /** Language the user sees when we have no better information. */
  public static final MailLocale DEFAULT = RU;

  /**
   * Resolves a stored {@code users.locale} value or a raw Accept-Language header. Accepts the
   * frontend's "kz" spelling as well as the ISO "kk", and tolerates full header syntax ({@code
   * "kk-KZ,ru;q=0.9"}) by reading the first tag.
   *
   * @return the matching locale, or {@link #DEFAULT} for null/unknown input
   */
  public static MailLocale from(String raw) {
    if (raw == null || raw.isBlank()) {
      return DEFAULT;
    }
    String tag = raw.trim().toLowerCase(Locale.ROOT).split("[,;]")[0].trim();
    String language = tag.split("-")[0];

    return switch (language) {
      case "kk", "kz" -> KK;
      case "en" -> EN;
      case "ru" -> RU;
      default -> DEFAULT;
    };
  }

  /** Canonical value to persist in {@code users.locale}. */
  public String tag() {
    return switch (this) {
      case RU -> "ru";
      case KK -> "kk";
      case EN -> "en";
    };
  }

  /** Picks the variant for this locale. Keeps call sites to a single readable line. */
  public String pick(String ru, String kk, String en) {
    return switch (this) {
      case RU -> ru;
      case KK -> kk;
      case EN -> en;
    };
  }
}
