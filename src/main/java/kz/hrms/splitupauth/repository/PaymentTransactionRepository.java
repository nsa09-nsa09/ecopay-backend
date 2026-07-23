package kz.hrms.splitupauth.repository;

import java.util.List;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PaymentTransactionRepository
    extends JpaRepository<PaymentTransaction, Long>, JpaSpecificationExecutor<PaymentTransaction> {
  List<PaymentTransaction> findByPaymentIntentOrderByCreatedAtAsc(PaymentIntent paymentIntent);

  boolean existsByRoomMember_IdAndStatus(Long roomMemberId, PaymentTransactionStatus status);

  List<PaymentTransaction> findByRoomMember_IdAndStatusAndTypeOrderByCreatedAtDesc(
      Long roomMemberId, PaymentTransactionStatus status, PaymentTransactionType type);
}
