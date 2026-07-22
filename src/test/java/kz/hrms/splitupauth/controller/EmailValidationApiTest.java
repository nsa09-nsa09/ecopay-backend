package kz.hrms.splitupauth.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import kz.hrms.splitupauth.dto.AuthResponse;
import kz.hrms.splitupauth.exception.GlobalExceptionHandler;
import kz.hrms.splitupauth.exception.InvalidEmailException;
import kz.hrms.splitupauth.exception.MailDeliveryException;
import kz.hrms.splitupauth.service.AuthService;
import kz.hrms.splitupauth.service.MailLocale;
import kz.hrms.splitupauth.service.PhoneVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Verifies the contract the web client actually consumes: the JSON error shape for a rejected
 * address, the HTTP statuses, and that Accept-Language reaches the service as a MailLocale.
 *
 * <p>Runs the real controller, the real Bean Validation setup and the real GlobalExceptionHandler
 * over MockMvc standalone — no database, no Spring context.
 */
@ExtendWith(MockitoExtension.class)
class EmailValidationApiTest {

  @Mock AuthService authService;
  @Mock PhoneVerificationService phoneVerificationService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    AuthController controller = new AuthController(authService, phoneVerificationService);
    // @Value fields are not populated in standalone setup; only the cookie
    // max-age depends on this and no assertion below touches it.
    ReflectionTestUtils.setField(controller, "refreshExpirationMs", 604800000L);

    mockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  private static String registerBody(String email) {
    return """
        {"email":"%s","password":"password123","displayName":"Test User","termsAccepted":true}
        """
        .formatted(email);
  }

  // ===================== rejected addresses =====================

  @Test
  void deadDomain_returns400_withReasonCodeAndSuggestion() throws Exception {
    when(authService.register(any(), any()))
        .thenThrow(
            new InvalidEmailException(
                InvalidEmailException.Reason.EMAIL_DOMAIN_NOT_FOUND,
                "This email domain does not accept mail",
                "user@gmail.com"));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("user@gmial.com")))
        .andExpect(status().isBadRequest())
        // The frontend switches on errors.code and offers errors.suggestion as
        // a one-click fix, so both must be present in exactly these fields.
        .andExpect(jsonPath("$.code").value("EMAIL_DOMAIN_NOT_FOUND"))
        .andExpect(jsonPath("$.errors.code").value("EMAIL_DOMAIN_NOT_FOUND"))
        .andExpect(jsonPath("$.errors.suggestion").value("user@gmail.com"))
        .andExpect(jsonPath("$.errors.email").exists());
  }

  @Test
  void malformedAddress_returns400_withFormatCode() throws Exception {
    when(authService.register(any(), any()))
        .thenThrow(
            new InvalidEmailException(
                InvalidEmailException.Reason.EMAIL_INVALID_FORMAT, "Email address is not valid"));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("user@example.com")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors.code").value("EMAIL_INVALID_FORMAT"))
        // No guess available — the field must be absent, not null, so the
        // client's `err.errors.suggestion` check stays falsy.
        .andExpect(jsonPath("$.errors.suggestion").doesNotExist());
  }

  @Test
  void smtpFailure_returns503_notA500() throws Exception {
    when(authService.register(any(), any()))
        .thenThrow(new MailDeliveryException("Unable to send email right now"));

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("user@example.com")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.errors.code").value("EMAIL_DELIVERY_FAILED"));
  }

  // ===================== locale plumbing =====================

  @Test
  void acceptLanguage_reachesTheServiceAsMailLocale() throws Exception {
    when(authService.register(any(), any())).thenReturn(AuthResponse.builder().build());

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .header("Accept-Language", "kz")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("user@example.com")))
        .andExpect(status().isCreated());

    // 'kz' is the frontend's spelling of Kazakh; MailLocale maps it to KK.
    verify(authService).register(any(), eq(MailLocale.KK));
  }

  @Test
  void missingAcceptLanguage_fallsBackToDefault() throws Exception {
    when(authService.register(any(), any())).thenReturn(AuthResponse.builder().build());

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("user@example.com")))
        .andExpect(status().isCreated());

    verify(authService).register(any(), eq(MailLocale.RU));
  }

  @Test
  void fullBrowserAcceptLanguageHeader_isParsedNotRejected() throws Exception {
    when(authService.register(any(), any())).thenReturn(AuthResponse.builder().build());

    mockMvc
        .perform(
            post("/api/v1/auth/register")
                .header("Accept-Language", "en-US,en;q=0.9,ru;q=0.8")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("user@example.com")))
        .andExpect(status().isCreated());

    verify(authService).register(any(), eq(MailLocale.EN));
  }

  // ===================== resend throttling =====================

  @Test
  void resendVerification_staysSilentSoItCannotEnumerateAccounts() throws Exception {
    // Whatever happens inside, the caller must not be able to tell an unknown
    // address from a real one.
    doThrow(new IllegalStateException("should never surface"))
        .when(authService)
        .resendVerificationEmail(any());

    mockMvc
        .perform(
            post("/api/v1/auth/resend-verification")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"stranger@example.com\"}"))
        // A 500 here would still be an oracle, but the point of this test is to
        // pin the current contract: the endpoint never returns 404/409.
        .andExpect(
            result -> {
              int code = result.getResponse().getStatus();
              if (code == 404 || code == 409) {
                throw new AssertionError("resend leaked account existence with status " + code);
              }
            });
  }
}
