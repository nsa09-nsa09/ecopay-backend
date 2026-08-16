package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface RefundTransactionRepository
    extends JpaRepository<RefundTransaction, Long>, JpaSpecificationExecutor<RefundTransaction> {

  Optional<RefundTransaction> findByIdempotencyKey(String idempotencyKey);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefundTransaction> findWithLockById(Long id);

  Optional<RefundTransaction> findByProviderRefundId(String providerRefundId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<RefundTransaction> findWithLockByProviderRefundId(String providerRefundId);

  List<RefundTransaction> findByDisputeOrderByCreatedAtDesc(Dispute dispute);

  List<RefundTransaction> findByPaymentTransaction_PaymentIntent_UserOrderByCreatedAtDesc(
      User user);

  long countByPaymentTransaction_PaymentIntent_UserAndStatusIn(
      User user, List<kz.hrms.splitupauth.entity.RefundStatus> statuses);

  List<RefundTransaction> findByPaymentTransactionAndStatusIn(
      PaymentTransaction tx, List<kz.hrms.splitupauth.entity.RefundStatus> statuses);

  @Query(
      """
      select r.id
      from RefundTransaction r
      where r.status = kz.hrms.splitupauth.entity.RefundStatus.PENDING
        and (r.nextRetryAt is null or r.nextRetryAt <= :now)
        and (r.leaseUntil is null or r.leaseUntil <= :now)
        and coalesce(r.retryCount, 0) < :maxAttempts
      order by coalesce(r.nextRetryAt, r.createdAt), r.id
      """)
  List<Long> findDispatchableIds(
      @Param("now") LocalDateTime now, @Param("maxAttempts") int maxAttempts, Pageable pageable);

  @Query(
      """
      select count(r) > 0
      from RefundTransaction r
      where r.paymentTransaction.paymentIntent = :intent
        and r.status in :statuses
      """)
  boolean existsByPaymentIntentAndStatusIn(
      @Param("intent") PaymentIntent intent,
      @Param("statuses") List<kz.hrms.splitupauth.entity.RefundStatus> statuses);

  default BigDecimal sumActiveRefundAmounts(PaymentTransaction tx) {
    return findByPaymentTransactionAndStatusIn(
            tx,
            List.of(
                kz.hrms.splitupauth.entity.RefundStatus.PENDING,
                kz.hrms.splitupauth.entity.RefundStatus.SUCCESS))
        .stream()
        .map(RefundTransaction::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}
