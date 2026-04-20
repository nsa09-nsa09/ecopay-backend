package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.RefreshToken;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);
    List<RefreshToken> findByUser(User user);
    void deleteByUser(User user);
    void deleteByExpiresAtBefore(LocalDateTime dateTime);
    void deleteByToken(String token);
}
