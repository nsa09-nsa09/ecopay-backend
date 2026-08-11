package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentReservation;
import kz.hrms.splitupauth.entity.PaymentReservationStatus;
import kz.hrms.splitupauth.entity.Room;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentReservationRepository extends JpaRepository<PaymentReservation, Long> {

  Optional<PaymentReservation> findByPaymentIntent(PaymentIntent paymentIntent);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select r from PaymentReservation r where r.paymentIntent.id = :paymentIntentId")
  Optional<PaymentReservation> findWithLockByPaymentIntentId(@Param("paymentIntentId") Long paymentIntentId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  Optional<PaymentReservation> findFirstByRoomMember_IdAndStatusOrderByCreatedAtDesc(
      Long roomMemberId, PaymentReservationStatus status);

  long countByRoomAndStatusAndExpiresAtAfter(
      Room room, PaymentReservationStatus status, LocalDateTime now);
}
