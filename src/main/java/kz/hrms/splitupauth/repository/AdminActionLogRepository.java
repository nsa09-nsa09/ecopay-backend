package kz.hrms.splitupauth.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AdminActionLogRepository
    extends JpaRepository<AdminActionLog, Long>, JpaSpecificationExecutor<AdminActionLog> {
  List<AdminActionLog> findAllByOrderByCreatedAtDesc();

  Optional<AdminActionLog> findFirstByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtDesc(
      String entityType, Long entityId, AdminActionType actionType);

  Optional<AdminActionLog> findFirstByEntityTypeAndEntityIdAndActionTypeInOrderByCreatedAtDesc(
      String entityType, Long entityId, Collection<AdminActionType> actionTypes);

  List<AdminActionLog> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
      String entityType, Long entityId, Pageable pageable);
}
