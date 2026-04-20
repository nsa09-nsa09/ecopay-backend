package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.Dispute;
import kz.hrms.splitupauth.entity.RefundTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefundTransactionRepository extends JpaRepository<RefundTransaction, Long> {

    Optional<RefundTransaction> findByIdempotencyKey(String idempotencyKey);

    List<RefundTransaction> findByDisputeOrderByCreatedAtDesc(Dispute dispute);
}