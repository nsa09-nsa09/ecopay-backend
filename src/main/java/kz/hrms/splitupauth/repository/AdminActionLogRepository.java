package kz.hrms.splitupauth.repository;

import java.util.List;
import kz.hrms.splitupauth.entity.AdminActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminActionLogRepository
    extends JpaRepository<AdminActionLog, Long>, JpaSpecificationExecutor<AdminActionLog> {
  List<AdminActionLog> findAllByOrderByCreatedAtDesc();
}
