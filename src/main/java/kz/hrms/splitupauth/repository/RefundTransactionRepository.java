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
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

  List<RefundTransaction> findByStatusInOrderByCreatedAtAsc(
      List<kz.hrms.splitupauth.entity.RefundStatus> statuses);

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
                kz.hrms.splitupauth.entity.RefundStatus.PENDING_MANUAL,
                kz.hrms.splitupauth.entity.RefundStatus.PENDING_PROVIDER,
                kz.hrms.splitupauth.entity.RefundStatus.SUCCESS,
                kz.hrms.splitupauth.entity.RefundStatus.SUCCESS_MANUAL))
        .stream()
        .map(RefundTransaction::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  default BigDecimal sumSuccessfulRefundAmounts(PaymentTransaction tx) {
    return findByPaymentTransactionAndStatusIn(
            tx,
            List.of(
                kz.hrms.splitupauth.entity.RefundStatus.SUCCESS,
                kz.hrms.splitupauth.entity.RefundStatus.SUCCESS_MANUAL))
        .stream()
        .map(RefundTransaction::getAmount)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Batch sum of active (PENDING + PENDING_PROVIDER + SUCCESS) refunds, grouped by the charge's
   * roomMember id. B3: paired with the sum of success charges per member, this is the admin-list
   * input for refundedAmountKzt / refundableAmountKzt without an N+1 scan.
   */
  @Query(
      """
      select t.roomMember.id, coalesce(sum(r.amount), 0)
      from RefundTransaction r join r.paymentTransaction t
      where t.roomMember.id in :roomMemberIds
        and r.status in (
          kz.hrms.splitupauth.entity.RefundStatus.PENDING,
          kz.hrms.splitupauth.entity.RefundStatus.PENDING_PROVIDER,
          kz.hrms.splitupauth.entity.RefundStatus.SUCCESS)
      group by t.roomMember.id
      """)
  List<Object[]> sumActiveRefundsByRoomMemberIds(
      @Param("roomMemberIds") java.util.Collection<Long> roomMemberIds);

  @Query(
      "select r.id from RefundTransaction r "
          + "where r.status = kz.hrms.splitupauth.entity.RefundStatus.PENDING_PROVIDER "
          + "and (r.lastReconciledAt is null or r.lastReconciledAt < :reconciledBefore) "
          + "order by r.createdAt asc")
  java.util.List<Long> findIdsPendingProviderForReconciliation(
      @Param("reconciledBefore") java.time.LocalDateTime reconciledBefore,
      org.springframework.data.domain.Pageable pageable);
}
