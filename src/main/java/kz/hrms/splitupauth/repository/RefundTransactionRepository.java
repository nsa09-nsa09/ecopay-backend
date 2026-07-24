package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.RefundTransaction;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;

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

  List<RefundTransaction> findByPaymentTransactionAndStatusIn(
      PaymentTransaction tx, List<kz.hrms.splitupauth.entity.RefundStatus> statuses);

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
