package kz.hrms.splitupauth.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Level-1 (format) rules, typo suggestions and log masking. */
class EmailNormalizerTest {

  // ===================== normalize =====================

  @Test
  void normalize_lowercasesAndStripsWhitespace() {
    assertEquals("user@gmail.com", EmailNormalizer.normalize("  User@Gmail.COM "));
  }

  @Test
  void normalize_stripsInvisibleWhitespaceFromPastedAddresses() {
    // Copying an address out of a chat client routinely drags along a
    // non-breaking or zero-width space, which a plain trim() leaves behind.
    assertEquals("user@mail.ru", EmailNormalizer.normalize("user@mail.ru​"));
    assertEquals("user@mail.ru", EmailNormalizer.normalize(" user@mail.ru"));
  }

  @Test
  void normalize_returnsNullForBlank() {
    assertNull(EmailNormalizer.normalize(null));
    assertNull(EmailNormalizer.normalize("   "));
  }

  // ===================== format =====================

  @ParameterizedTest
  @ValueSource(
      strings = {
        "user@gmail.com",
        "first.last@yandex.ru",
        "user+tag@mail.kz",
        "a_b-c@sub.domain.co.uk",
        "user123@narxoz.kz"
      })
  void accepts_wellFormedAddresses(String email) {
    assertTrue(EmailNormalizer.isStructurallyValid(email), email);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "plainstring", // no @
        "@gmail.com", // no local part
        "user@", // no domain
        "user@localhost", // no TLD
        "user@gmail..com", // double dot
        "user..name@gmail.com", // double dot in local part
        ".user@gmail.com", // leading dot
        "user.@gmail.com", // trailing dot in local part
        "user@gmail.com.", // trailing dot
        "user@-gmail.com", // domain starts with hyphen
        "user@gmail.c", // one-letter TLD
        "us er@gmail.com", // internal space
        "a@b@gmail.com" // two @
      })
  void rejects_malformedAddresses(String email) {
    assertFalse(EmailNormalizer.isStructurallyValid(email), email);
  }

  @Test
  void rejects_addressesOverRfcLengthLimits() {
    assertFalse(EmailNormalizer.isStructurallyValid("a".repeat(65) + "@gmail.com"));
    assertFalse(EmailNormalizer.isStructurallyValid("a".repeat(250) + "@gmail.com"));
  }

  // ===================== typo suggestions =====================

  @ParameterizedTest
  @ValueSource(strings = {"gmial.com", "gmai.com", "gmail.co", "gnail.com", "gmail.con"})
  void suggests_gmailForCommonMisspellings(String domain) {
    assertEquals("user@gmail.com", EmailNormalizer.suggestCorrection("user@" + domain));
  }

  @Test
  void suggests_correctionsForOtherPopularProviders() {
    assertEquals("user@mail.ru", EmailNormalizer.suggestCorrection("user@mai.ru"));
    assertEquals("user@yandex.ru", EmailNormalizer.suggestCorrection("user@yandx.ru"));
    assertEquals("user@outlook.com", EmailNormalizer.suggestCorrection("user@outlok.com"));
  }

  @Test
  void suggests_nothingForCorrectOrUnrelatedDomains() {
    assertNull(EmailNormalizer.suggestCorrection("user@gmail.com"));
    assertNull(EmailNormalizer.suggestCorrection("user@ecosplit.kz"));
  }

  @Test
  void suggests_nothingForShortDomainsOneEditAway() {
    // "bk.ru" and "vk.ru" differ by one character, but vk.ru is a plausible
    // real domain — guessing here would nag users with correct addresses.
    assertNull(EmailNormalizer.suggestCorrection("user@vk.ru"));
  }

  // ===================== masking =====================

  @Test
  void mask_hidesTheLocalPartButKeepsTheDomain() {
    assertEquals("a*******r@gmail.com", EmailNormalizer.mask("alexander@gmail.com"));
    assertEquals("**@gmail.com", EmailNormalizer.mask("ab@gmail.com"));
    assertEquals("<none>", EmailNormalizer.mask(null));
  }
}
