package kz.hrms.splitupauth.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Registration and login accept only email + password for the regular account flow. */
class RegisterRequestValidationTest {

  private static jakarta.validation.ValidatorFactory factory;
  private static Validator validator;

  @BeforeAll
  static void setUp() {
    factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @AfterAll
  static void tearDown() {
    factory.close();
  }

  private static RegisterRequest register(String email) {
    RegisterRequest req = new RegisterRequest();
    req.setEmail(email);
    req.setPassword("Test1234");
    req.setDisplayName("User");
    req.setTermsAccepted(true);
    return req;
  }

  @Test
  void emailOnly_isValid() {
    assertTrue(validator.validate(register("user@test.kz")).isEmpty());
  }

  @Test
  void withoutEmail_isRejected() {
    assertFalse(validator.validate(register(null)).isEmpty());
  }

  @Test
  void phoneOnlyRegistration_isRejectedBecauseEmailIsRequired() {
    RegisterRequest req = new RegisterRequest();
    req.setPassword("Test1234");
    req.setDisplayName("User");
    req.setTermsAccepted(true);

    assertFalse(validator.validate(req).isEmpty());
  }

  @Test
  void malformedEmail_isRejected() {
    assertFalse(validator.validate(register("not-an-email")).isEmpty());
  }

  @Test
  void loginRequest_requiresEmail() {
    LoginRequest emailLogin = new LoginRequest();
    emailLogin.setEmail("user@test.kz");
    emailLogin.setPassword("secret");
    assertTrue(validator.validate(emailLogin).isEmpty());

    LoginRequest phoneOnly = new LoginRequest();
    phoneOnly.setPassword("secret");
    assertFalse(validator.validate(phoneOnly).isEmpty());
  }
}
