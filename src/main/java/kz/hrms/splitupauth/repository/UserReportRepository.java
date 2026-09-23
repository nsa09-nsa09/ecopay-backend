package kz.hrms.splitupauth.repository;

import java.util.Collection;
import java.util.List;
import kz.hrms.splitupauth.entity.UserReport;
import kz.hrms.splitupauth.entity.UserReportCategory;
import kz.hrms.splitupauth.entity.UserReportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UserReportRepository
    extends JpaRepository<UserReport, Long>, JpaSpecificationExecutor<UserReport> {
  @Override
  @EntityGraph(attributePaths = {"reporter", "targetUser", "assignedAdmin"})
  Page<UserReport> findAll(Specification<UserReport> spec, Pageable pageable);

  boolean existsByReporter_IdAndTargetUser_IdAndCategoryAndStatusIn(
      Long reporterId,
      Long targetId,
      UserReportCategory category,
      Collection<UserReportStatus> statuses);

  @EntityGraph(attributePaths = {"reporter", "targetUser", "assignedAdmin"})
  List<UserReport> findByTargetUser_IdOrderByCreatedAtDesc(Long targetId, Pageable pageable);

  @EntityGraph(attributePaths = {"reporter", "targetUser", "assignedAdmin"})
  List<UserReport> findByReporter_IdOrderByCreatedAtDesc(Long reporterId, Pageable pageable);
}
