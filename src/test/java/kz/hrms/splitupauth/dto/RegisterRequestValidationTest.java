package kz.hrms.splitupauth.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Registration accepts exactly one identifier — email OR phone — now that email is optional and
 * phone sign-up exists. Login follows the same contract.
 */
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

  private static RegisterRequest register(String email, String phone) {
    RegisterRequest req = new RegisterRequest();
    req.setEmail(email);
    req.setPhone(phone);
    req.setPassword("Test1234");
    req.setDisplayName("User");
    req.setTermsAccepted(true);
    return req;
  }

  @Test
  void emailOnly_isValid() {
    assertTrue(validator.validate(register("user@test.kz", null)).isEmpty());
  }

  @Test
  void phoneOnly_isValid() {
    assertTrue(validator.validate(register(null, "+77001234567")).isEmpty());
  }

  @Test
  void neitherIdentifier_isRejected() {
    assertFalse(validator.validate(register(null, null)).isEmpty());
  }

  @Test
  void bothIdentifiers_areRejected() {
    assertFalse(validator.validate(register("user@test.kz", "+77001234567")).isEmpty());
  }

  @Test
  void malformedPhone_isRejected() {
    assertFalse(validator.validate(register(null, "87001234567")).isEmpty());
  }

  @Test
  void malformedEmail_isRejected() {
    assertFalse(validator.validate(register("not-an-email", null)).isEmpty());
  }

  @Test
  void loginRequest_followsTheSameOneOfContract() {
    LoginRequest emailLogin = new LoginRequest();
    emailLogin.setEmail("user@test.kz");
    emailLogin.setPassword("secret");
    assertTrue(validator.validate(emailLogin).isEmpty());

    LoginRequest phoneLogin = new LoginRequest();
    phoneLogin.setPhone("+77001234567");
    phoneLogin.setPassword("secret");
    assertTrue(validator.validate(phoneLogin).isEmpty());

    LoginRequest empty = new LoginRequest();
    empty.setPassword("secret");
    assertFalse(validator.validate(empty).isEmpty());
  }
}
