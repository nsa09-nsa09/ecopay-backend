package kz.hrms.splitupauth.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SlugGeneratorTest {

  @Test
  void latinInputLowercasedAndDashed() {
    assertEquals("john-doe", SlugGenerator.normalize("John Doe"));
    assertEquals("mario123", SlugGenerator.normalize("Mario123"));
  }

  @Test
  void russianCyrillicTransliterated() {
    assertEquals("ivan-petrov", SlugGenerator.normalize("Иван Петров"));
    assertEquals("zhenya", SlugGenerator.normalize("Женя"));
    assertEquals("schuka", SlugGenerator.normalize("Щука"));
  }

  @Test
  void kazakhLettersTransliterated() {
    // ә→a, ғ→g, қ→q, ң→ng, ө→o, ұ→u, ү→u, һ→h, і→i
    assertEquals("aqniet", SlugGenerator.normalize("Әқниет"));
    assertEquals("nurgul", SlugGenerator.normalize("Нұрғұл"));
    assertEquals("otep", SlugGenerator.normalize("Өтеп"));
  }

  @Test
  void emojiAndPunctuationCollapseToFallback() {
    assertEquals("user", SlugGenerator.normalize("😀"));
    assertEquals("user", SlugGenerator.normalize("!!!"));
    assertEquals("user", SlugGenerator.normalize(""));
    assertEquals("user", SlugGenerator.normalize(null));
  }

  @Test
  void tooShortCollapsesToFallback() {
    // Less than 3 chars post-normalization → "user".
    assertEquals("user", SlugGenerator.normalize("ab"));
    assertEquals("user", SlugGenerator.normalize("а"));
  }

  @Test
  void longStringTruncatedToThirtyCharsAndNoTrailingDash() {
    String big = "supercalifragilisticexpialidocious-and-then-some";
    String out = SlugGenerator.normalize(big);
    assertTrue(out.length() <= 30, "length was " + out.length());
    assertFalse(out.endsWith("-"), "should not end with a dash");
  }

  @Test
  void runsOfPunctuationCollapseToSingleDash() {
    assertEquals("hello-world", SlugGenerator.normalize("hello   ---   world"));
    assertEquals("a1-b2", SlugGenerator.normalize("a1 !! b2"));
  }

  @Test
  void deterministicAcrossCalls() {
    assertEquals(SlugGenerator.normalize("Даулет"), SlugGenerator.normalize("Даулет"));
  }
}
