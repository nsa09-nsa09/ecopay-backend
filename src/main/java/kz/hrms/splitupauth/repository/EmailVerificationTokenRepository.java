package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.EmailVerificationToken;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface EmailVerificationTokenRepository extends JpaRepository<EmailVerificationToken, Long> {
    Optional<EmailVerificationToken> findByToken(String token);
    void deleteByUser(User user);
    void deleteByExpiresAtBefore(LocalDateTime dateTime);
}
