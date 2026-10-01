package kz.hrms.splitupauth.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.PayoutBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, Long> {

  @Query(
      "select b from PayoutBatch b where b.status = 'PENDING_PROVIDER' "
          + "and b.providerPayoutId is not null "
          + "and (b.nextRetryAt is null or b.nextRetryAt <= :now) "
          + "order by b.createdAt asc")
  List<PayoutBatch> findProviderPendingForReconciliation(@Param("now") LocalDateTime now);

  @Query(
      "select b from PayoutBatch b where b.status = 'PROCESSING' "
          + "and b.providerPayoutId is null "
          + "and (b.nextRetryAt is null or b.nextRetryAt <= :now) "
          + "and (b.leaseUntil is null or b.leaseUntil <= :now) "
          + "order by b.createdAt asc")
  List<PayoutBatch> findRetryableForDispatch(@Param("now") LocalDateTime now);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from PayoutBatch b where b.id = :id")
  Optional<PayoutBatch> findWithLockById(@Param("id") Long id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from PayoutBatch b where b.providerPayoutId = :providerPayoutId")
  Optional<PayoutBatch> findWithLockByProviderPayoutId(
      @Param("providerPayoutId") String providerPayoutId);
}
