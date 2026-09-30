package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PayoutRepository extends JpaRepository<Payout, Long> {
    List<Payout> findByUserOrderByCreatedAtDesc(User user);

    List<Payout> findByStatusInOrderByCreatedAtAsc(List<String> statuses);

    /** Locked: webhook settlement must serialize with dispatch and refund reversal. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payout> findByProviderPayoutId(String providerPayoutId);

    /** Locked: refund reversal must serialize with the dispatcher's claim. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Payout> findByTriggeringPaymentIntent(PaymentIntent triggeringPaymentIntent);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payout p where p.id = :id")
    Optional<Payout> findWithLockById(@Param("id") Long id);

    /** Claimed for dispatch but never recorded a result (crash between claim and result). */
    @Query("""
            select p.id from Payout p
            where p.status = 'PROCESSING'
              and p.providerPayoutId is null
              and coalesce(p.updatedAt, p.createdAt) < :cutoff
            """)
    List<Long> findStuckProcessingIds(@Param("cutoff") LocalDateTime cutoff);
}
