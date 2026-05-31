package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.EmailVerificationToken;
import kz.hrms.splitupauth.entity.PasswordResetToken;
import kz.hrms.splitupauth.entity.RefreshToken;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.*;
import kz.hrms.splitupauth.repository.EmailVerificationTokenRepository;
import kz.hrms.splitupauth.repository.PasswordResetTokenRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kz.hrms.splitupauth.entity.Role;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final EmailVerificationTokenRepository emailVerificationTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;
    private final RateLimitService rateLimitService;
    private final UserMapper userMapper;
    private final PhoneVerificationService phoneVerificationService;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException("User with this email already exists");
        }

        if (userRepository.existsByPhone(request.getPhone())) {
            throw new PhoneAlreadyExistsException("User with this phone already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName())
                .phone(request.getPhone())
                .status(UserStatus.ACTIVE)
                .role(Role.USER)
                .reputation(0)
                .emailVerified(false)
                .build();

        user = userRepository.save(user);

<<<<<<< HEAD
        sendVerificationEmail(user);
=======
        try {
            phoneVerificationService.requestCode(user, request.getPhone());
        } catch (Exception ex) {
            // SMS delivery failure must not block registration — user can resend.
            log.warn("Failed to send initial SMS code for user {}: {}",
                    user.getEmail(), ex.getMessage());
        }

        String accessToken = jwtUtil.generateAccessToken(user.getEmail());
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);
>>>>>>> origin/feat/freedompay-mvp-integration

        // No tokens issued: the account must verify its email before logging in.
        return AuthResponse.builder()
                .user(userMapper.toDto(user))
                .build();
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        rateLimitService.checkLoginAttempts(request.getEmail());

        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> {
                    rateLimitService.recordLoginAttempt(request.getEmail(), false);
                    return new InvalidCredentialsException("Invalid email or password");
                });

        if (user.getStatus() == UserStatus.BANNED) {
            throw new UserBannedException("Your account has been banned");
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            rateLimitService.recordLoginAttempt(request.getEmail(), false);
            throw new InvalidCredentialsException("Invalid email or password");
        }

        if (Boolean.FALSE.equals(user.getEmailVerified())) {
            throw new EmailNotVerifiedException("Email not verified. Please check your inbox for the verification link.");
        }

        rateLimitService.recordLoginAttempt(request.getEmail(), true);

        String accessToken = jwtUtil.generateAccessToken(user.getEmail());
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .user(userMapper.toDto(user))
                .build();
    }

    @Transactional
    public AuthResponse refreshToken(RefreshTokenRequest request) {
        RefreshToken refreshToken = refreshTokenService.validateRefreshToken(request.getRefreshToken());
        User user = refreshToken.getUser();

        if (user.getStatus() == UserStatus.BANNED) {
            throw new UserBannedException("Your account has been banned");
        }

        String accessToken = jwtUtil.generateAccessToken(user.getEmail());
        RefreshToken newRefreshToken = refreshTokenService.createRefreshToken(user);

        refreshTokenService.revokeRefreshToken(request.getRefreshToken());

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(newRefreshToken.getToken())
                .user(userMapper.toDto(user))
                .build();
    }

    @Transactional
    public void logout(String refreshTokenValue) {
        refreshTokenService.revokeRefreshToken(refreshTokenValue);
    }


@Transactional
public void requestPasswordReset(PasswordResetRequest request) {
    User user = userRepository.findByEmail(request.getEmail()).orElse(null);

    if (user == null) {
        return;
    }

    passwordResetTokenRepository.deleteByUser(user);

    String token = UUID.randomUUID().toString();
    PasswordResetToken resetToken = PasswordResetToken.builder()
            .token(token)
            .user(user)
            .expiresAt(LocalDateTime.now().plusHours(1))
            .used(false)
            .build();

    passwordResetTokenRepository.save(resetToken);

    emailService.sendPasswordResetEmail(user.getEmail(), token);
    }
    @Transactional
    public void confirmPasswordReset(PasswordResetConfirmRequest request) {

        PasswordResetToken resetToken = passwordResetTokenRepository
                .findByToken(request.getToken())
                .orElseThrow(() ->
                        new TokenExpiredException("Invalid or expired reset token"));

        if (resetToken.getUsed()) {
            throw new TokenExpiredException("Reset token already used");
        }

        if (resetToken.isExpired()) {
            throw new TokenExpiredException("Reset token expired");
        }

        User user = resetToken.getUser();

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(user);

        resetToken.setUsed(true);
        passwordResetTokenRepository.save(resetToken);

        refreshTokenService.revokeAllUserTokens(user);
    }

    @Transactional
    public void verifyEmail(String token) {
        EmailVerificationToken verificationToken = emailVerificationTokenRepository
                .findByToken(token)
                .orElseThrow(() ->
                        new TokenExpiredException("Invalid or expired verification token"));

        if (verificationToken.getUsed()) {
            throw new TokenExpiredException("Verification token already used");
        }

        if (verificationToken.isExpired()) {
            throw new TokenExpiredException("Verification token expired");
        }

        User user = verificationToken.getUser();
        user.setEmailVerified(true);
        userRepository.save(user);

        verificationToken.setUsed(true);
        emailVerificationTokenRepository.save(verificationToken);
    }

    @Transactional
    public void resendVerificationEmail(ResendVerificationRequest request) {
        User user = userRepository.findByEmail(request.getEmail()).orElse(null);

        // Stay silent for unknown or already-verified accounts to avoid email enumeration.
        if (user == null || Boolean.TRUE.equals(user.getEmailVerified())) {
            return;
        }

        sendVerificationEmail(user);
    }

    private void sendVerificationEmail(User user) {
        emailVerificationTokenRepository.deleteByUser(user);

        String token = UUID.randomUUID().toString();
        EmailVerificationToken verificationToken = EmailVerificationToken.builder()
                .token(token)
                .user(user)
                .expiresAt(LocalDateTime.now().plusHours(24))
                .used(false)
                .build();

        emailVerificationTokenRepository.save(verificationToken);

        emailService.sendVerificationEmail(user.getEmail(), token);
    }
}
