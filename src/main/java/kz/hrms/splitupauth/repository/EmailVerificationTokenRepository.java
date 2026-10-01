package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.entity.EmailVerificationToken;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationToken, Long> {
  Optional<EmailVerificationToken> findByToken(String token);

  Optional<EmailVerificationToken> findByUser(User user);

  void deleteByUser(User user);

  void deleteByExpiresAtBefore(LocalDateTime dateTime);

  /** Send-frequency guard for the email add/change flow (cooldown + hourly cap). */
  long countByUserAndCreatedAtAfter(User user, LocalDateTime after);

  Optional<EmailVerificationToken> findTopByUserOrderByCreatedAtDesc(User user);
}
