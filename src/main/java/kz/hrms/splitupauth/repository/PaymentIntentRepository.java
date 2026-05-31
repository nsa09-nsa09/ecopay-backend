package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {
    Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);
    Optional<PaymentIntent> findFirstByRoomMemberOrderByCreatedAtDesc(RoomMember roomMember);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PaymentIntent> findWithLockById(Long id);

    Optional<PaymentIntent> findByExternalPaymentId(String externalPaymentId);

    List<PaymentIntent> findByStatusAndExpiresAtBefore(PaymentIntentStatus status, LocalDateTime cutoff);
}
