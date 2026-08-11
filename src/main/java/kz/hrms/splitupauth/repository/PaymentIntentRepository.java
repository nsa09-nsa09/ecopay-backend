package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, Long> {
  Optional<PaymentIntent> findByIdempotencyKey(String idempotencyKey);

  Optional<PaymentIntent> findFirstByRoomMemberOrderByCreatedAtDesc(RoomMember roomMember);

  Optional<PaymentIntent> findFirstByRoomMemberAndStatusOrderByCreatedAtDesc(
      RoomMember roomMember, PaymentIntentStatus status);

  Optional<PaymentIntent> findFirstByRoomMember_IdAndStatusInOrderByCreatedAtDesc(
      Long roomMemberId, List<PaymentIntentStatus> statuses);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PaymentIntent> findWithLockById(Long id);

  Optional<PaymentIntent> findByExternalPaymentId(String externalPaymentId);

  List<PaymentIntent> findByStatusAndExpiresAtBefore(
      PaymentIntentStatus status, LocalDateTime cutoff);

  @Modifying
  @Query(
      """
      update PaymentIntent p
         set p.status = kz.hrms.splitupauth.entity.PaymentIntentStatus.SUCCESS,
             p.externalPaymentId = coalesce(:externalPaymentId, p.externalPaymentId),
             p.providerStatusCode = coalesce(:providerStatusCode, p.providerStatusCode)
       where p.id = :id
         and p.status = kz.hrms.splitupauth.entity.PaymentIntentStatus.PENDING
      """)
  int markPendingSuccess(
      @Param("id") Long id,
      @Param("externalPaymentId") String externalPaymentId,
      @Param("providerStatusCode") String providerStatusCode);
}
