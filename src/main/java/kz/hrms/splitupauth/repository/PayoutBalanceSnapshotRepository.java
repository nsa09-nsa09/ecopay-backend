package kz.hrms.splitupauth.repository;

import java.util.Optional;
import kz.hrms.splitupauth.entity.PayoutBalanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutBalanceSnapshotRepository
    extends JpaRepository<PayoutBalanceSnapshot, Long> {

  Optional<PayoutBalanceSnapshot> findTopByOrderByCreatedAtDesc();
}
