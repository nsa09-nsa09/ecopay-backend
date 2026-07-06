package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {
  List<LoginAttempt> findByEmailAndAttemptTimeAfter(String email, LocalDateTime afterTime);

  void deleteByAttemptTimeBefore(LocalDateTime beforeTime);
}
