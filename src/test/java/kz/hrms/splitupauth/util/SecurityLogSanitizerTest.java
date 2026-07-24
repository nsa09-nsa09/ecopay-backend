package kz.hrms.splitupauth.util;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecurityLogSanitizerTest {

  @Test
  void sanitizerRedactsKnownSensitivePairs() {
    String sanitized =
        SecurityLogSanitizer.sanitize(
            "Authorization=Bearer abc Cookie=session=secret refresh_token=rt "
                + "pg_card_token=card verification_code=123456 revealed_identifier=+77051234567");

    assertFalse(sanitized.contains("abc"));
    assertFalse(sanitized.contains("rt"));
    assertFalse(sanitized.contains("card"));
    assertFalse(sanitized.contains("123456"));
    assertFalse(sanitized.contains("+77051234567"));
    assertTrue(sanitized.contains("[redacted]"));
  }
}
