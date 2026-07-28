package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository
    extends JpaRepository<PaymentTransaction, Long>, JpaSpecificationExecutor<PaymentTransaction> {
  List<PaymentTransaction> findByPaymentIntentOrderByCreatedAtAsc(PaymentIntent paymentIntent);

  Optional<PaymentTransaction> findFirstByPaymentIntentAndTypeAndStatus(
      PaymentIntent paymentIntent, PaymentTransactionType type, PaymentTransactionStatus status);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from PaymentTransaction t where t.id = :id")
  Optional<PaymentTransaction> findWithLockById(@Param("id") Long id);

  boolean existsByRoomMember_IdAndStatus(Long roomMemberId, PaymentTransactionStatus status);

  List<PaymentTransaction> findByRoomMember_IdAndStatusAndTypeOrderByCreatedAtDesc(
      Long roomMemberId, PaymentTransactionStatus status, PaymentTransactionType type);

  /** Captured member charges in a room, used after a confirmed owner breach. */
  List<PaymentTransaction> findByRoom_IdAndStatusAndTypeOrderByCreatedAtAsc(
      Long roomId, PaymentTransactionStatus status, PaymentTransactionType type);
}
