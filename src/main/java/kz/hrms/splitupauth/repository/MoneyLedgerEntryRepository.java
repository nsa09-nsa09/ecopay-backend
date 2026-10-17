package kz.hrms.splitupauth.repository;

import java.util.Optional;
import kz.hrms.splitupauth.entity.MoneyLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MoneyLedgerEntryRepository extends JpaRepository<MoneyLedgerEntry, Long> {
  Optional<MoneyLedgerEntry> findByIdempotencyKey(String idempotencyKey);
}
