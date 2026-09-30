package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FreedomWebhookInboxRepository extends JpaRepository<FreedomWebhookInbox, Long> {

    Optional<FreedomWebhookInbox> findByProviderRequestId(String providerRequestId);

    /** Row lock: one processor per inbox row at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from FreedomWebhookInbox i where i.id = :id")
    Optional<FreedomWebhookInbox> findWithLockById(@Param("id") Long id);

    /** Signature-valid rows whose processing never completed (crash / transient failure). */
    @Query("""
            select i.id from FreedomWebhookInbox i
            where i.processingStatus in :statuses
              and i.signatureValid = true
              and i.receivedAt < :receivedBefore
              and i.attempts < :maxAttempts
            order by i.receivedAt
            """)
    List<Long> findRetryableIds(@Param("statuses") Collection<String> statuses,
                                @Param("receivedBefore") LocalDateTime receivedBefore,
                                @Param("maxAttempts") int maxAttempts,
                                Pageable page);
}
