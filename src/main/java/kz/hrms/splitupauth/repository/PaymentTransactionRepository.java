package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentTransaction;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    List<PaymentTransaction> findByPaymentIntentOrderByCreatedAtAsc(PaymentIntent paymentIntent);
    boolean existsByRoomMember_IdAndStatus(Long roomMemberId, PaymentTransactionStatus status);
}