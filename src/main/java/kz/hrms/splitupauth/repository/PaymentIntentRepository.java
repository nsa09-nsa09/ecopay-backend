package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    /** Ids only: callers re-read each row under its lock instead of trusting a stale snapshot. */
    @Query("select pi.id from PaymentIntent pi where pi.status = :status and pi.expiresAt < :cutoff order by pi.id")
    List<Long> findIdsByStatusAndExpiresAtBefore(@Param("status") PaymentIntentStatus status,
                                                 @Param("cutoff") LocalDateTime cutoff);

    List<PaymentIntent> findByRoomMemberAndStatus(RoomMember roomMember, PaymentIntentStatus status);

    /** Room of an intent, read without loading (and thus without caching) the entity graph. */
    @Query("select pi.roomMember.room.id from PaymentIntent pi where pi.id = :id")
    Optional<Long> findRoomIdById(@Param("id") Long id);

    /**
     * Seats temporarily held by other applicants: an APPLIED membership with an
     * INITIAL intent that is still PENDING and not yet expired.
     */
    @Query("""
            select count(pi) from PaymentIntent pi
            where pi.roomMember.room.id = :roomId
              and pi.roomMember.id <> :excludeMemberId
              and pi.roomMember.deletedAt is null
              and pi.roomMember.status = kz.hrms.splitupauth.entity.MemberStatus.APPLIED
              and pi.status = kz.hrms.splitupauth.entity.PaymentIntentStatus.PENDING
              and pi.purpose = kz.hrms.splitupauth.entity.PaymentPurpose.INITIAL
              and pi.expiresAt > :now
            """)
    long countLiveSeatReservations(@Param("roomId") Long roomId,
                                   @Param("excludeMemberId") Long excludeMemberId,
                                   @Param("now") LocalDateTime now);
}
