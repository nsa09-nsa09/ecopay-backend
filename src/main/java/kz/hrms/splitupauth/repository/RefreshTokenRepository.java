package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.RefreshToken;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {
  Optional<RefreshToken> findByToken(String token);

  /** Row-locked lookup so two parallel refreshes with one token cannot both rotate it. */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from RefreshToken t where t.token = :token")
  Optional<RefreshToken> findWithLockByToken(@Param("token") String token);

  List<RefreshToken> findByUser(User user);

  void deleteByUser(User user);

  void deleteByExpiresAtBefore(LocalDateTime dateTime);

  void deleteByToken(String token);
}
