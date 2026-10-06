package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.User;
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

  long countByUserAndStatusIn(User user, List<PaymentIntentStatus> statuses);

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

  /**
   * Open intents due for a provider status query: older than {@code createdBefore}, not queried
   * since {@code reconciledBefore}, under the attempt cap. Oldest first, bounded by the page.
   */
  @Query(
      """
      select p.id from PaymentIntent p
       where p.status in :statuses
         and p.providerName = :providerName
         and p.createdAt < :createdBefore
         and (p.lastReconciledAt is null or p.lastReconciledAt < :reconciledBefore)
         and coalesce(p.reconcileAttempts, 0) < :maxAttempts
       order by p.createdAt asc
      """)
  List<Long> findIdsForProviderReconciliation(
      @Param("statuses") List<PaymentIntentStatus> statuses,
      @Param("providerName") String providerName,
      @Param("createdBefore") LocalDateTime createdBefore,
      @Param("reconciledBefore") LocalDateTime reconciledBefore,
      @Param("maxAttempts") int maxAttempts,
      org.springframework.data.domain.Pageable pageable);
}
