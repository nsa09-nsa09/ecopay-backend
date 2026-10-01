package kz.hrms.splitupauth.repository;

import java.util.Optional;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.PayoutBlock;
import kz.hrms.splitupauth.entity.PayoutBlockSourceType;
import kz.hrms.splitupauth.entity.PayoutBlockStatus;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PayoutBlockRepository extends JpaRepository<PayoutBlock, Long> {

  boolean existsByPayoutAndStatus(Payout payout, PayoutBlockStatus status);

  Optional<PayoutBlock> findByPayoutAndSourceTypeAndSourceId(
      Payout payout, PayoutBlockSourceType sourceType, Long sourceId);
}
