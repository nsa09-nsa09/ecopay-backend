package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PasswordResetToken;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {
  Optional<PasswordResetToken> findByToken(String token);

  void deleteByUser(User user);

  void deleteByExpiresAtBefore(LocalDateTime dateTime);
}
