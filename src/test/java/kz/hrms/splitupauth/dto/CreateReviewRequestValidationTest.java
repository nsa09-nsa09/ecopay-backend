package kz.hrms.splitupauth.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** Guards the 1..10 bounds of the peer-review trust rating. */
class CreateReviewRequestValidationTest {

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

  private static CreateReviewRequest request(Integer rating) {
    CreateReviewRequest req = new CreateReviewRequest();
    req.setRecipientId(2L);
    req.setRoomId(3L);
    req.setRating(rating);
    return req;
  }

  private static boolean hasRatingViolation(Set<ConstraintViolation<CreateReviewRequest>> v) {
    return v.stream().anyMatch(cv -> cv.getPropertyPath().toString().equals("rating"));
  }

  @Test
  void ratingZeroIsRejected() {
    assertTrue(hasRatingViolation(validator.validate(request(0))));
  }

  @Test
  void ratingOneIsAccepted() {
    assertFalse(hasRatingViolation(validator.validate(request(1))));
  }

  @Test
  void ratingTenIsAccepted() {
    assertFalse(hasRatingViolation(validator.validate(request(10))));
  }

  @Test
  void ratingElevenIsRejected() {
    assertTrue(hasRatingViolation(validator.validate(request(11))));
  }

  @Test
  void ratingNullIsRejected() {
    assertTrue(hasRatingViolation(validator.validate(request(null))));
  }
}
