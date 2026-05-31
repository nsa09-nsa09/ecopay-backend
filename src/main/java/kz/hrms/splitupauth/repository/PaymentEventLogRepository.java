package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.PaymentEventLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PaymentEventLogRepository extends JpaRepository<PaymentEventLog, Long> {
}
