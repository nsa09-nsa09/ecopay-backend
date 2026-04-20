package kz.hrms.splitupauth.repository;

import kz.hrms.splitupauth.entity.AdminActionLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;


public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long>, JpaSpecificationExecutor<AdminActionLog> {
    List<AdminActionLog> findAllByOrderByCreatedAtDesc();
}