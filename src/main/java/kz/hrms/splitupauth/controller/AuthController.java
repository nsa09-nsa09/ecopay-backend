package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.AuthService;
import kz.hrms.splitupauth.service.PhoneVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PhoneVerificationService phoneVerificationService;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /**
     * Step 2 of the ADMIN / SUPPORT login: caller submits the challenge id
     * returned by /login plus the 6-digit code that was emailed to them.
     */
    @PostMapping("/login/2fa/verify")
    public ResponseEntity<AuthResponse> verifyTwoFactor(
            @Valid @RequestBody TwoFactorVerifyRequest request) {
        return ResponseEntity.ok(authService.verifyStaffTwoFactor(request));
    }

    /**
     * Re-issues the OTP for an outstanding staff 2FA challenge. Cooldown
     * protected by the service layer.
     */
    @PostMapping("/login/2fa/resend")
    public ResponseEntity<Void> resendTwoFactor(
            @Valid @RequestBody TwoFactorResendRequest request) {
        authService.resendStaffTwoFactor(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refreshToken(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refreshToken(request));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/reset-password")
    public ResponseEntity<Void> requestPasswordReset(@Valid @RequestBody PasswordResetRequest request) {
        authService.requestPasswordReset(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset-password/confirm")
    public ResponseEntity<Void> confirmPasswordReset(@Valid @RequestBody PasswordResetConfirmRequest request) {
        authService.confirmPasswordReset(request);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/verify-email")
    public ResponseEntity<String> verifyEmail(@RequestParam("token") String token) {
        authService.verifyEmail(token);
        return ResponseEntity.ok("Email verified successfully. You can now log in.");
    }

    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@Valid @RequestBody ResendVerificationRequest request) {
        authService.resendVerificationEmail(request);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/phone/request-code")
    public ResponseEntity<Void> requestPhoneCode(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody RequestPhoneCodeRequest request
    ) {
        phoneVerificationService.requestCode(user, request.getPhone());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/phone/verify")
    public ResponseEntity<Void> verifyPhone(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody VerifyPhoneRequest request
    ) {
        phoneVerificationService.verifyCode(user, request.getPhone(), request.getCode());
        return ResponseEntity.noContent().build();
    }
}
