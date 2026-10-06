package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.LoginAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoginAttemptRepository extends JpaRepository<LoginAttempt, Long> {
  List<LoginAttempt> findByEmailAndAttemptTimeAfter(String email, LocalDateTime afterTime);

  long countByEmailAndSuccessfulFalseAndAttemptTimeAfter(String email, LocalDateTime afterTime);

  long countByIpAddressAndSuccessfulFalseAndAttemptTimeAfter(
      String ipAddress, LocalDateTime afterTime);

  /** Account deletion: keeps the security counters but drops the login identifier. */
  @Modifying
  @Query("update LoginAttempt a set a.email = :replacement where a.email = :identifier")
  int anonymizeIdentifier(
      @Param("identifier") String identifier, @Param("replacement") String replacement);

  /** Bulk delete: a derived delete would load every expired row into memory first. */
  @Modifying
  @Query("delete from LoginAttempt a where a.attemptTime < :beforeTime")
  int deleteByAttemptTimeBefore(@Param("beforeTime") LocalDateTime beforeTime);
}
