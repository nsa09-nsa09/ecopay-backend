package kz.hrms.splitupauth.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Block 8: every user-triggerable exception carries a stable SCREAMING_SNAKE {@code code}. A
 * code-carrying domain exception surfaces its own code; a plain one falls back to the type default.
 * HTTP statuses and messages are unchanged — this only asserts the additive code field.
 */
class GlobalExceptionHandlerCodeTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void domainCodeIsSurfaced_whenProvided() {
    ErrorResponse body =
        handler.handleInvalidRequest(new InvalidRequestException("ROOM_FULL", "full")).getBody();
    assertEquals("ROOM_FULL", body.getCode());
    assertEquals("full", body.getMessage());
    assertEquals(400, body.getStatus());
  }

  @Test
  void typeDefaultCode_whenNoDomainCode() {
    assertEquals(
        "INVALID_REQUEST",
        handler.handleInvalidRequest(new InvalidRequestException("x")).getBody().getCode());
    assertEquals(
        "FORBIDDEN_OPERATION",
        handler.handleForbidden(new ForbiddenOperationException("x")).getBody().getCode());
  }

  @Test
  void rateLimitAndVerificationCodesAreStable() {
    assertEquals(
        "RATE_LIMITED",
        handler.handleTooManyRequests(new TooManyRequestsException("x")).getBody().getCode());
    assertEquals(
        "LOGIN_RATE_LIMITED",
        handler
            .handleTooManyAttempts(new TooManyLoginAttemptsException("x"))
            .getBody()
            .getCode());
    assertEquals(
        "INVALID_VERIFICATION_CODE",
        handler
            .handleInvalidCode(new InvalidVerificationCodeException("x"))
            .getBody()
            .getCode());
  }
}
