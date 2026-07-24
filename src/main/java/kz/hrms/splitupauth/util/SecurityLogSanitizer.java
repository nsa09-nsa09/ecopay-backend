package kz.hrms.splitupauth.util;

import java.util.regex.Pattern;

public final class SecurityLogSanitizer {

  private static final Pattern SENSITIVE_PAIR =
      Pattern.compile(
          "(?i)(authorization|cookie|access[_-]?token|refresh[_-]?token|jwt|token|verification[_-]?code|code|pg_card_token|pg_recurring_profile_id|card[_-]?binding[_-]?token|revealed[_-]?identifier|password|secret|api[_-]?key)=([^\\s&]+)");
  private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)Bearer\\s+[^\\s&]+");

  private SecurityLogSanitizer() {}

  public static String sanitize(String value) {
    if (value == null || value.isBlank()) {
      return value;
    }
    String withoutBearer = BEARER_TOKEN.matcher(value).replaceAll("Bearer [redacted]");
    return SENSITIVE_PAIR.matcher(withoutBearer).replaceAll("$1=[redacted]");
  }

  public static String tokenPresent(String value) {
    return value == null || value.isBlank() ? "absent" : "present";
  }
}
