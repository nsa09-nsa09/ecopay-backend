package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FreedomWebhookInboxRepository extends JpaRepository<FreedomWebhookInbox, Long> {

  Optional<FreedomWebhookInbox> findByProviderRequestId(String providerRequestId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select i from FreedomWebhookInbox i where i.id = :id")
  Optional<FreedomWebhookInbox> findWithLockById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      """
      select i
      from FreedomWebhookInbox i
      where i.processingStatus in ('PENDING','FAILED')
        and (i.nextRetryAt is null or i.nextRetryAt <= :now)
        and (i.leaseUntil is null or i.leaseUntil < :now)
      order by i.receivedAt asc
      """)
  List<FreedomWebhookInbox> findRetryable(@Param("now") LocalDateTime now);
}
