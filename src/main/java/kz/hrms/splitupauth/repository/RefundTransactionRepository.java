package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundTransactionRepository extends JpaRepository<RefundTransaction, Long> {

    Optional<RefundTransaction> findByIdempotencyKey(String idempotencyKey);

    /** Locked: webhook, admin and user paths settle the same refund row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<RefundTransaction> findByProviderRefundId(String providerRefundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundTransaction r where r.id = :id")
    Optional<RefundTransaction> findWithLockById(@Param("id") Long id);

    List<RefundTransaction> findByDisputeOrderByCreatedAtDesc(Dispute dispute);

    List<RefundTransaction> findByPaymentTransaction_PaymentIntent_UserOrderByCreatedAtDesc(User user);

    List<RefundTransaction> findByPaymentTransactionAndStatusIn(PaymentTransaction tx, List<RefundStatus> statuses);

    @Query("""
            select coalesce(sum(r.amount), 0) from RefundTransaction r
            where r.paymentTransaction = :tx and r.status in :statuses
            """)
    BigDecimal sumAmounts(@Param("tx") PaymentTransaction tx, @Param("statuses") Collection<RefundStatus> statuses);

    /** Money committed to refunds (in flight or done): what is no longer refundable. */
    default BigDecimal sumActiveRefundAmounts(PaymentTransaction tx) {
        return sumAmounts(tx, List.of(RefundStatus.PENDING, RefundStatus.SUCCESS));
    }

    /** Money actually returned to the payer. */
    default BigDecimal sumSuccessfulRefundAmounts(PaymentTransaction tx) {
        return sumAmounts(tx, List.of(RefundStatus.SUCCESS));
    }
}
