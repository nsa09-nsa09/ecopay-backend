package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {
  List<LoginAttempt> findByEmailAndAttemptTimeAfter(String email, LocalDateTime afterTime);

  /**
   * Indexed COUNT of failed attempts for one email in the window (uses idx_email_attempt). Replaces
   * the old load-all-rows-then-stream-and-count, which pulled every attempt row into the heap.
   */
  long countByEmailAndSuccessfulFalseAndAttemptTimeAfter(String email, LocalDateTime afterTime);

  /** Indexed COUNT of failed attempts from one source IP in the window (uses idx_login_attempt_ip). */
  long countByIpAndSuccessfulFalseAndAttemptTimeAfter(String ip, LocalDateTime afterTime);

  void deleteByAttemptTimeBefore(LocalDateTime beforeTime);
}
