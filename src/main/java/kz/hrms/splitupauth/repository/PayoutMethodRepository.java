package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PayoutMethod;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PayoutMethodRepository extends JpaRepository<PayoutMethod, Long> {

  /** Account deletion: no payout may ever be routed to a deleted user's card again. */
  @Modifying
  @Query(
      "update PayoutMethod m set m.status = 'REVOKED', m.isDefault = false, m.revokedAt = :now"
          + " where m.user.id = :userId and m.status <> 'REVOKED'")
  int revokeAllForUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);

  List<PayoutMethod> findByUserAndStatusOrderByIsDefaultDescCreatedAtDesc(User user, String status);

  Optional<PayoutMethod> findByUserAndIsDefaultTrueAndStatus(User user, String status);

  Optional<PayoutMethod> findByUserAndProviderCardTokenAndStatus(
      User user, String providerCardToken, String status);
}
