package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.RefreshToken;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.TokenExpiredException;
import kz.hrms.splitupauth.repository.RefreshTokenRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtUtil jwtUtil;
    private final TokenRevocationService tokenRevocationService;

    @Value("${jwt.refresh-expiration}")
    private Long refreshExpiration;

    /**
     * Issues a new refresh token. The raw token is returned to the caller (sent to the
     * client); only its SHA-256 hash is persisted, so a DB leak cannot reveal live tokens.
     */
    @Transactional
    public String createRefreshToken(User user) {
        String rawToken = UUID.randomUUID().toString();

        RefreshToken refreshToken = RefreshToken.builder()
                .token(hashToken(rawToken))
                .user(user)
                .expiresAt(LocalDateTime.now().plusSeconds(refreshExpiration / 1000))
                .build();

        refreshTokenRepository.save(refreshToken);
        return rawToken;
    }

    @Transactional
    public RefreshToken validateRefreshToken(String rawToken) {
        RefreshToken refreshToken = refreshTokenRepository.findByToken(hashToken(rawToken))
                .orElseThrow(() -> new TokenExpiredException("Invalid refresh token"));

        if (refreshToken.getRevoked()) {
            // Reuse of an already-rotated/revoked token = suspected token theft.
            // Revoke the entire session so neither party can keep using it.
            log.warn("Refresh token reuse detected for user {} — revoking all sessions",
                    refreshToken.getUser().getId());
            // Commit the revocation in a separate transaction: this request will fail and
            // roll back, but the session-wide revocation must survive.
            tokenRevocationService.revokeAllUserTokens(refreshToken.getUser());
            throw new TokenExpiredException("Refresh token has been revoked");
        }

        if (refreshToken.isExpired()) {
            throw new TokenExpiredException("Refresh token has expired");
        }

        return refreshToken;
    }

    @Transactional
    public void revokeRefreshToken(String rawToken) {
        refreshTokenRepository.findByToken(hashToken(rawToken)).ifPresent(refreshToken -> {
            refreshToken.setRevoked(true);
            refreshTokenRepository.save(refreshToken);
        });
    }

    private String hashToken(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    @Transactional
    public void revokeAllUserTokens(User user) {
        refreshTokenRepository.findByUser(user).forEach(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }

    @Transactional
    public void cleanupExpiredTokens() {
        refreshTokenRepository.deleteByExpiresAtBefore(LocalDateTime.now());
    }
}
