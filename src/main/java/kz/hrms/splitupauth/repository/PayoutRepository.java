package kz.hrms.splitupauth.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {
  List<Payout> findByUserOrderByCreatedAtDesc(User user);

  List<Payout> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

  /** Payouts in a dispatchable status whose hold window has elapsed (due now). */
  @Query(
      "SELECT p FROM Payout p WHERE p.status IN :statuses "
          + "AND (p.releaseAt IS NULL OR p.releaseAt <= :now) "
          + "ORDER BY p.createdAt ASC")
  List<Payout> findDispatchable(
      @Param("statuses") List<String> statuses, @Param("now") LocalDateTime now);

  Optional<Payout> findByProviderPayoutId(String providerPayoutId);

  Optional<Payout> findByTriggeringPaymentIntent(PaymentIntent triggeringPaymentIntent);
}
