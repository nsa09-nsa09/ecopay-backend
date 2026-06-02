package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.AuthResponse;
import kz.hrms.splitupauth.dto.LoginRequest;
import kz.hrms.splitupauth.dto.TwoFactorVerifyRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallenge;
import kz.hrms.splitupauth.entity.StaffTwoFactorChallengeStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidCredentialsException;
import kz.hrms.splitupauth.exception.UserBannedException;
import kz.hrms.splitupauth.repository.EmailVerificationTokenRepository;
import kz.hrms.splitupauth.repository.PasswordResetTokenRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validates the new ADMIN/SUPPORT 2FA login behaviour.
 *
 * <p>Active staff endpoints exposed by this flow:</p>
 * <ul>
 *   <li>{@code POST /api/v1/auth/login} — step 1; staff get a challenge, USER gets tokens.</li>
 *   <li>{@code POST /api/v1/auth/login/2fa/verify} — step 2; staff exchange a code for tokens.</li>
 *   <li>{@code POST /api/v1/auth/login/2fa/resend} — re-issue the OTP, cooldown protected.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
    @Mock private EmailVerificationTokenRepository emailVerificationTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private RefreshTokenService refreshTokenService;
    @Mock private EmailService emailService;
    @Mock private RateLimitService rateLimitService;
    @Mock private UserMapper userMapper;
    @Mock private PhoneVerificationService phoneVerificationService;
    @Mock private StaffTwoFactorService staffTwoFactorService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userRepository,
                passwordResetTokenRepository,
                emailVerificationTokenRepository,
                passwordEncoder,
                jwtUtil,
                refreshTokenService,
                emailService,
                rateLimitService,
                userMapper,
                phoneVerificationService,
                staffTwoFactorService
        );
    }

    @Test
    void userLogin_doesNotRequire2FA_andReturnsTokens() {
        User user = user(Role.USER);
        stubSuccessfulCredentials(user);
        when(staffTwoFactorService.requiresTwoFactor(user)).thenReturn(false);
        stubTokens(user);

        AuthResponse response = authService.login(loginRequest(user.getEmail()));

        assertEquals("access", response.getAccessToken());
        assertEquals("refresh", response.getRefreshToken());
        assertNotNull(response.getUser());
        assertNull(response.getRequiresTwoFactor());
        assertNull(response.getChallengeId());
        verify(staffTwoFactorService, never()).createChallenge(any());
        verify(rateLimitService).recordLoginAttempt(user.getEmail(), true);
    }

    @Test
    void adminLogin_requires2FA_andDoesNotReturnTokens() {
        User user = user(Role.ADMIN);
        stubSuccessfulCredentials(user);
        when(staffTwoFactorService.requiresTwoFactor(user)).thenReturn(true);
        StaffTwoFactorChallenge challenge = challenge(user);
        when(staffTwoFactorService.createChallenge(user)).thenReturn(challenge);

        AuthResponse response = authService.login(loginRequest(user.getEmail()));

        assertTrue(Boolean.TRUE.equals(response.getRequiresTwoFactor()));
        assertEquals(challenge.getId(), response.getChallengeId());
        assertEquals(challenge.getExpiresAt(), response.getExpiresAt());
        assertNotNull(response.getMaskedEmail());
        assertNull(response.getAccessToken());
        assertNull(response.getRefreshToken());
        assertNull(response.getUser());
        verify(jwtUtil, never()).generateAccessToken(anyString());
        verify(refreshTokenService, never()).createRefreshToken(any());
    }

    @Test
    void supportLogin_requires2FA_andDoesNotReturnTokens() {
        User user = user(Role.SUPPORT);
        stubSuccessfulCredentials(user);
        when(staffTwoFactorService.requiresTwoFactor(user)).thenReturn(true);
        when(staffTwoFactorService.createChallenge(user)).thenReturn(challenge(user));

        AuthResponse response = authService.login(loginRequest(user.getEmail()));

        assertTrue(Boolean.TRUE.equals(response.getRequiresTwoFactor()));
        assertNull(response.getAccessToken());
        assertNull(response.getRefreshToken());
    }

    @Test
    void invalidPassword_doesNotCreateChallenge_andThrows() {
        User user = user(Role.ADMIN);
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", user.getPassword())).thenReturn(false);

        assertThrows(InvalidCredentialsException.class,
                () -> authService.login(loginRequestWithPassword(user.getEmail(), "wrong")));

        verify(rateLimitService).recordLoginAttempt(user.getEmail(), false);
        verify(staffTwoFactorService, never()).requiresTwoFactor(any());
        verify(staffTwoFactorService, never()).createChallenge(any());
    }

    @Test
    void verifyStaffTwoFactor_returnsTokens_onSuccess() {
        User user = user(Role.ADMIN);
        when(staffTwoFactorService.verifyChallenge("c-1", "123456")).thenReturn(user);
        stubTokens(user);

        TwoFactorVerifyRequest req = new TwoFactorVerifyRequest();
        req.setChallengeId("c-1");
        req.setCode("123456");

        AuthResponse response = authService.verifyStaffTwoFactor(req);

        assertEquals("access", response.getAccessToken());
        assertEquals("refresh", response.getRefreshToken());
        assertNotNull(response.getUser());
    }

    @Test
    void verifyStaffTwoFactor_rejects_bannedUser() {
        User user = user(Role.ADMIN);
        user.setStatus(UserStatus.BANNED);
        when(staffTwoFactorService.verifyChallenge("c-1", "123456")).thenReturn(user);

        TwoFactorVerifyRequest req = new TwoFactorVerifyRequest();
        req.setChallengeId("c-1");
        req.setCode("123456");

        assertThrows(UserBannedException.class, () -> authService.verifyStaffTwoFactor(req));
        verify(jwtUtil, never()).generateAccessToken(anyString());
    }

    private void stubSuccessfulCredentials(User user) {
        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret", user.getPassword())).thenReturn(true);
    }

    private void stubTokens(User user) {
        when(jwtUtil.generateAccessToken(user.getEmail())).thenReturn("access");
        // createRefreshToken returns the token string (String-based refresh-token model).
        when(refreshTokenService.createRefreshToken(eq(user))).thenReturn("refresh");
        when(userMapper.toDto(user)).thenReturn(UserDto.builder()
                .id(user.getId())
                .email(user.getEmail())
                .role(user.getRole())
                .build());
    }

    private static User user(Role role) {
        return User.builder()
                .id(role == Role.USER ? 1L : 2L)
                .email(role.name().toLowerCase() + "@example.com")
                .password("ENC")
                .displayName(role.name())
                .role(role)
                .status(UserStatus.ACTIVE)
                .reputation(0)
                .emailVerified(true)
                .build();
    }

    private static LoginRequest loginRequest(String email) {
        return loginRequestWithPassword(email, "secret");
    }

    private static LoginRequest loginRequestWithPassword(String email, String password) {
        LoginRequest req = new LoginRequest();
        req.setEmail(email);
        req.setPassword(password);
        return req;
    }

    private static StaffTwoFactorChallenge challenge(User user) {
        return StaffTwoFactorChallenge.builder()
                .id("c-1")
                .user(user)
                .codeHash("HASH")
                .status(StaffTwoFactorChallengeStatus.PENDING)
                .attempts(0)
                .createdAt(LocalDateTime.now())
                .lastSentAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
    }
}
