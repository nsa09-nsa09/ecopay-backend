package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.time.Duration;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.service.AuthService;
import kz.hrms.splitupauth.service.MailLocale;
import kz.hrms.splitupauth.service.PhoneVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

  /** Name of the httpOnly cookie that carries the refresh token to /auth/refresh and /logout. */
  private static final String REFRESH_COOKIE_NAME = "ecopay_rt";

  /**
   * Scoped to /api/v1/auth so the cookie is only ever attached to the two endpoints that read it.
   * Keeps it out of every unrelated backend request.
   */
  private static final String REFRESH_COOKIE_PATH = "/api/v1/auth";

  private final AuthService authService;
  private final PhoneVerificationService phoneVerificationService;

  /**
   * Refresh-token TTL from application config, in milliseconds (matches RefreshTokenService). We
   * convert to seconds for cookie maxAge below.
   */
  @Value("${jwt.refresh-expiration}")
  private long refreshExpirationMs;

  @PostMapping("/register")
  public ResponseEntity<AuthResponse> register(
      @Valid @RequestBody RegisterRequest request,
      HttpServletRequest httpRequest,
      HttpServletResponse response,
      @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
    // Captured on the account so every later email (verification, password
    // reset, notifications) goes out in the language the user signed up in.
    AuthResponse auth = authService.register(request, MailLocale.from(acceptLanguage), httpRequest);
    // Regular sign-up issues no tokens — the account must confirm the emailed
    // code first. Only the dev auto-verify path returns a refresh token here.
    if (auth.getRefreshToken() != null) {
      addRefreshCookie(response, auth.getRefreshToken());
    }
    return ResponseEntity.status(HttpStatus.CREATED).body(auth);
  }

  /**
   * Final step of regular registration: caller submits their email plus the 6-digit code that was
   * emailed to them. On success the account is verified and logged in (tokens + cookie issued).
   */
  @PostMapping("/verify-email-code")
  public ResponseEntity<AuthResponse> verifyEmailCode(
      @Valid @RequestBody VerifyEmailCodeRequest request, HttpServletResponse response) {
    AuthResponse auth = authService.verifyEmailCode(request);
    if (auth.getRefreshToken() != null) {
      addRefreshCookie(response, auth.getRefreshToken());
    }
    return ResponseEntity.ok(auth);
  }

  /**
   * Final step of phone registration: caller submits their phone plus the 6-digit code that was
   * sent by SMS. On success the account is verified and logged in (tokens + cookie issued). Mirrors
   * /verify-email-code.
   */
  @PostMapping("/verify-phone-code")
  public ResponseEntity<AuthResponse> verifyPhoneCode(
      @Valid @RequestBody VerifyPhoneCodeRequest request, HttpServletResponse response) {
    AuthResponse auth = authService.verifyPhoneCode(request);
    if (auth.getRefreshToken() != null) {
      addRefreshCookie(response, auth.getRefreshToken());
    }
    return ResponseEntity.ok(auth);
  }

  /**
   * Re-issue the SMS code for an unfinished phone registration. Always 200 (silent for unknown or
   * verified phones) to avoid phone-number enumeration. Mirrors /resend-verification.
   */
  @PostMapping("/resend-phone-code")
  public ResponseEntity<Void> resendPhoneCode(
      @Valid @RequestBody RequestPhoneCodeRequest request, HttpServletRequest httpRequest) {
    authService.resendPhoneCode(request.getPhone(), httpRequest);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/login")
  public ResponseEntity<AuthResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
    AuthResponse auth = authService.login(request);
    // Staff 2FA path does not issue tokens yet — only set the cookie when a
    // real refresh token was minted (regular users, or the second 2FA step).
    if (auth.getRefreshToken() != null) {
      addRefreshCookie(response, auth.getRefreshToken());
    }
    return ResponseEntity.ok(auth);
  }

  /**
   * Step 2 of the ADMIN / SUPPORT login: caller submits the challenge id returned by /login plus
   * the 6-digit code that was emailed to them.
   */
  @PostMapping("/login/2fa/verify")
  public ResponseEntity<AuthResponse> verifyTwoFactor(
      @Valid @RequestBody TwoFactorVerifyRequest request, HttpServletResponse response) {
    AuthResponse auth = authService.verifyStaffTwoFactor(request);
    if (auth.getRefreshToken() != null) {
      addRefreshCookie(response, auth.getRefreshToken());
    }
    return ResponseEntity.ok(auth);
  }

  /**
   * Re-issues the OTP for an outstanding staff 2FA challenge. Cooldown protected by the service
   * layer.
   */
  @PostMapping("/login/2fa/resend")
  public ResponseEntity<Void> resendTwoFactor(@Valid @RequestBody TwoFactorResendRequest request) {
    authService.resendStaffTwoFactor(request);
    return ResponseEntity.noContent().build();
  }

  /**
   * Refresh the access token. The refresh token is preferred from the httpOnly cookie (new client),
   * but we still accept it in the request body so an older frontend that hasn't been redeployed yet
   * keeps working.
   */
  @PostMapping("/refresh")
  public ResponseEntity<AuthResponse> refreshToken(
      @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieToken,
      @RequestBody(required = false) RefreshTokenRequest body,
      HttpServletResponse response) {
    String token = pickRefreshToken(cookieToken, body);
    if (token == null) {
      throw new InvalidRequestException("Refresh token is required");
    }
    RefreshTokenRequest req = new RefreshTokenRequest();
    req.setRefreshToken(token);
    AuthResponse auth = authService.refreshToken(req);
    if (auth.getRefreshToken() != null) {
      addRefreshCookie(response, auth.getRefreshToken());
    }
    return ResponseEntity.ok(auth);
  }

  /**
   * Revoke the refresh token and clear the cookie. Accepts the token from cookie first, body
   * second, and no-ops silently if neither is present (client already forgot it).
   */
  @PostMapping("/logout")
  public ResponseEntity<Void> logout(
      @CookieValue(name = REFRESH_COOKIE_NAME, required = false) String cookieToken,
      @RequestBody(required = false) RefreshTokenRequest body,
      HttpServletResponse response) {
    String token = pickRefreshToken(cookieToken, body);
    if (token != null) {
      authService.logout(token);
    }
    clearRefreshCookie(response);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/reset-password")
  public ResponseEntity<Void> requestPasswordReset(
      @Valid @RequestBody PasswordResetRequest request) {
    authService.requestPasswordReset(request);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/reset-password/confirm")
  public ResponseEntity<Void> confirmPasswordReset(
      @Valid @RequestBody PasswordResetConfirmRequest request) {
    authService.confirmPasswordReset(request);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/verify-email")
  public ResponseEntity<String> verifyEmail(@RequestParam("token") String token) {
    authService.verifyEmail(token);
    return ResponseEntity.ok("Email verified successfully. You can now log in.");
  }

  @PostMapping("/resend-verification")
  public ResponseEntity<Void> resendVerification(
      @Valid @RequestBody ResendVerificationRequest request) {
    authService.resendVerificationEmail(request);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/phone/request-code")
  public ResponseEntity<Void> requestPhoneCode(
      @AuthenticationPrincipal User user,
      @Valid @RequestBody RequestPhoneCodeRequest request,
      HttpServletRequest httpRequest) {
    phoneVerificationService.requestCode(user, request.getPhone(), httpRequest);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/phone/verify")
  public ResponseEntity<Void> verifyPhone(
      @AuthenticationPrincipal User user, @Valid @RequestBody VerifyPhoneRequest request) {
    phoneVerificationService.verifyCode(user, request.getPhone(), request.getCode());
    return ResponseEntity.noContent().build();
  }

  private String pickRefreshToken(String cookieToken, RefreshTokenRequest body) {
    if (cookieToken != null && !cookieToken.isBlank()) {
      return cookieToken;
    }
    if (body != null && body.getRefreshToken() != null && !body.getRefreshToken().isBlank()) {
      return body.getRefreshToken();
    }
    return null;
  }

  private void addRefreshCookie(HttpServletResponse response, String refreshToken) {
    ResponseCookie cookie =
        ResponseCookie.from(REFRESH_COOKIE_NAME, refreshToken)
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(Duration.ofSeconds(refreshExpirationMs / 1000))
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }

  private void clearRefreshCookie(HttpServletResponse response) {
    ResponseCookie cookie =
        ResponseCookie.from(REFRESH_COOKIE_NAME, "")
            .httpOnly(true)
            .secure(true)
            .sameSite("Lax")
            .path(REFRESH_COOKIE_PATH)
            .maxAge(0)
            .build();
    response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
  }
}
