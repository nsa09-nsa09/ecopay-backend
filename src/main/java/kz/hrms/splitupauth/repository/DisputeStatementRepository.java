package kz.hrms.splitupauth.repository;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.DisputeStatement;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DisputeStatementRepository extends JpaRepository<DisputeStatement, Long> {

  List<DisputeStatement> findByDisputeOrderByCreatedAtAsc(Dispute dispute);

  Optional<DisputeStatement> findByDisputeAndSide(Dispute dispute, String side);
}
