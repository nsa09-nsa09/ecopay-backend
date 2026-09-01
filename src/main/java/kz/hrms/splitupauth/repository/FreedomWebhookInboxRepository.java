package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FreedomWebhookInboxRepository
    extends JpaRepository<FreedomWebhookInbox, Long>, JpaSpecificationExecutor<FreedomWebhookInbox> {

  Optional<FreedomWebhookInbox> findByProviderRequestId(String providerRequestId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select i from FreedomWebhookInbox i where i.id = :id and i.leaseOwner = :leaseOwner and i.processingStatus = 'PROCESSING'")
  Optional<FreedomWebhookInbox> findClaimedWithLockById(
      @Param("id") Long id, @Param("leaseOwner") String leaseOwner);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from FreedomWebhookInbox i where i.id = :id")
  Optional<FreedomWebhookInbox> findWithLockById(@Param("id") Long id);

  @Query(
      """
      select i.id
      from FreedomWebhookInbox i
      where (
          i.processingStatus in ('PENDING','FAILED')
          and (i.nextRetryAt is null or i.nextRetryAt <= :now)
        ) or (
          i.processingStatus = 'PROCESSING'
          and (i.leaseUntil is null or i.leaseUntil <= :now)
        )
      order by i.receivedAt asc
      """)
  List<Long> findRetryableIds(@Param("now") LocalDateTime now, Pageable pageable);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      update FreedomWebhookInbox i
      set i.processingStatus = 'PROCESSING',
          i.leaseOwner = :leaseOwner,
          i.leaseUntil = :leaseUntil,
          i.lastAttemptAt = :now,
          i.attemptCount = i.attemptCount + 1,
          i.nextRetryAt = null
      where i.id = :id and (
        (
          i.processingStatus in ('PENDING','FAILED')
          and (i.nextRetryAt is null or i.nextRetryAt <= :now)
        ) or (
          i.processingStatus = 'PROCESSING'
          and (i.leaseUntil is null or i.leaseUntil <= :now)
        )
      )
      """)
  int claimForProcessing(
      @Param("id") Long id,
      @Param("leaseOwner") String leaseOwner,
      @Param("now") LocalDateTime now,
      @Param("leaseUntil") LocalDateTime leaseUntil);
}
