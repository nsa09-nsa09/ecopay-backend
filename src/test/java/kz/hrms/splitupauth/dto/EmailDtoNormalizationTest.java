package kz.hrms.splitupauth.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Regression cover for a bug found by running the app: {@code @Email} rejects an address with
 * surrounding whitespace, and it runs <em>before</em> any service-layer normalization. A pasted
 * address with a trailing space — routine, since copying out of another app drags whitespace along
 * — was therefore turned away with an unhelpful "Email must be valid".
 *
 * <p>The DTO setters now canonicalise on bind, so the constraint sees the cleaned value. These
 * tests pin that ordering.
 */
class EmailDtoNormalizationTest {

  private static ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void initValidator() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void closeValidator() {
    factory.close();
  }

  @Test
  void registerRequest_trimsAndLowercases_soValidationSeesTheCleanValue() {
    RegisterRequest request = new RegisterRequest();
    request.setEmail("  Kazakh.User@GMAIL.com  ");
    request.setPassword("password123");
    request.setDisplayName("Kazakh User");
    request.setTermsAccepted(true);

    assertEquals("kazakh.user@gmail.com", request.getEmail());
    assertTrue(
        validator.validateProperty(request, "email").isEmpty(),
        "@Email must accept the normalized value");
  }

  @Test
  void loginRequest_normalizes_soCaseVariantsHitTheSameStoredRow() {
    LoginRequest request = new LoginRequest();
    request.setEmail("EN.User@Gmail.Com");

    assertEquals("en.user@gmail.com", request.getEmail());
    assertEquals("en.user@gmail.com", request.identifier());
  }

  @Test
  void emailChangeRequest_normalizes() {
    EmailChangeRequest request = new EmailChangeRequest();
    request.setEmail(" New.Address@Yandex.RU ");

    assertEquals("new.address@yandex.ru", request.getEmail());
  }

  @Test
  void blankInput_becomesNull_soAbsentAndEmptyCollapse() {
    // The optional-identifier check on RegisterRequest treats null and "" the
    // same; normalizing "" to null keeps that assumption true.
    RegisterRequest request = new RegisterRequest();
    request.setEmail("   ");

    assertNull(request.getEmail());
  }

  @Test
  void genuinelyMalformedInput_stillFailsValidation() {
    // Normalizing must not paper over real problems: stripping whitespace from
    // "user @gmail.com" would silently repair it, so confirm the remaining
    // structural checks still reject junk.
    RegisterRequest request = new RegisterRequest();
    request.setEmail("not-an-email");

    assertTrue(validator.validateProperty(request, "email").size() > 0);
  }
}
