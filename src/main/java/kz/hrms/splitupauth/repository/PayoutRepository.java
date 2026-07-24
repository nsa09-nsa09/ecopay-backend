package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {
  List<Payout> findByUserOrderByCreatedAtDesc(User user);

  List<Payout> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

  /**
   * Owner payouts that are still inside their hold window. Terminal, reversed, due, and already
   * processing payouts are deliberately excluded by the status/release predicates.
   */
  List<Payout> findByUserAndCurrencyAndStatusInAndReleaseAtAfterOrderByReleaseAtAsc(
      User user, String currency, List<String> statuses, LocalDateTime releaseAt);

  /** Payouts in a dispatchable status whose hold window has elapsed (due now). */
  @Query(
      "SELECT p FROM Payout p WHERE p.status IN :statuses "
          + "AND (p.releaseAt IS NULL OR p.releaseAt <= :now) "
          + "ORDER BY p.createdAt ASC")
  List<Payout> findDispatchable(
      @Param("statuses") List<String> statuses, @Param("now") LocalDateTime now);

  Optional<Payout> findByProviderPayoutId(String providerPayoutId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payout p where p.providerPayoutId = :providerPayoutId")
  Optional<Payout> findWithLockByProviderPayoutId(@Param("providerPayoutId") String providerPayoutId);

  Optional<Payout> findByTriggeringPaymentIntent(PaymentIntent triggeringPaymentIntent);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Payout p where p.id = :id")
  Optional<Payout> findWithLockById(@Param("id") Long id);
}
