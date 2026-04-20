package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.*;
import kz.hrms.splitupauth.entity.PasswordResetToken;
import kz.hrms.splitupauth.entity.RefreshToken;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.*;
import kz.hrms.splitupauth.repository.PasswordResetTokenRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import kz.hrms.splitupauth.entity.Role;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RefreshTokenService refreshTokenService;
    private final EmailService emailService;
    private final RateLimitService rateLimitService;
    private final UserMapper userMapper;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException("User with this email already exists");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .displayName(request.getDisplayName())
                .status(UserStatus.ACTIVE)
                .role(Role.USER)
                .reputation(0)
                .build();

        user = userRepository.save(user);

        String accessToken = jwtUtil.generateAccessToken(user.getEmail());
        RefreshToken refreshToken = refreshTokenService.createRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
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
}
