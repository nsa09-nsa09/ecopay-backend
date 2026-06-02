package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes all of a user's refresh tokens in its OWN transaction.
 *
 * <p>Used by the refresh-token reuse detection: the offending request fails
 * (its transaction rolls back), but the session-wide revocation must still
 * commit — hence REQUIRES_NEW on a separate bean so the Spring proxy applies.
 */
@Service
@RequiredArgsConstructor
public class TokenRevocationService {

    private final RefreshTokenRepository refreshTokenRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeAllUserTokens(User user) {
        refreshTokenRepository.findByUser(user).forEach(token -> {
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        });
    }
}
