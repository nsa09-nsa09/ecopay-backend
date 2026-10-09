package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.dto.CreateUserReportRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.UserReportDto;
import kz.hrms.splitupauth.dto.UserReportStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.UserReportRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.TextSanitizer;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserReportService {
  private static final int MAX_REPORTS_PER_DAY = 10;

  private final UserReportRepository reportRepository;
  private final UserRepository userRepository;
  private final AdminActionLogRepository auditRepository;

  @Transactional
  public UserReportDto create(User reporter, String handle, CreateUserReportRequest request) {
    if (reporter == null) {
      throw new ForbiddenOperationException("Active account required");
    }
    // Serialize submissions by this reporter, including reports against different users.
    reporter =
        userRepository
            .findByIdForUpdate(reporter.getId())
            .orElseThrow(() -> new ForbiddenOperationException("Active account required"));
    if (reporter.getStatus() != UserStatus.ACTIVE) {
      throw new ForbiddenOperationException("Active account required");
    }
    User found =
        userRepository
            .findBySlug(handle)
            .or(() -> userRepository.findByPublicId(handle))
            .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    User target = found;
    if (target.getStatus() == UserStatus.DELETED) {
      throw new ResourceNotFoundException("User not found");
    }
    if (target.getId().equals(reporter.getId())) {
      throw new InvalidRequestException("Cannot report yourself");
    }
    String description = TextSanitizer.sanitize(request.description());
    if (description.length() < 20) {
      throw new InvalidRequestException("Report description must contain at least 20 characters");
    }
    if (reportRepository.existsByReporter_IdAndTargetUser_IdAndCategoryAndStatusIn(
        reporter.getId(),
        target.getId(),
        request.category(),
        List.of(UserReportStatus.OPEN, UserReportStatus.IN_REVIEW))) {
      throw new ResourceConflictException("USER_REPORT_ALREADY_OPEN", "Report already open");
    }
    if (reportRepository.countByReporter_IdAndCreatedAtAfter(
            reporter.getId(), LocalDateTime.now().minusDays(1))
        >= MAX_REPORTS_PER_DAY) {
      throw new TooManyRequestsException("Too many user reports. Try again later.");
    }
    UserReport report = new UserReport();
    report.setReporter(reporter);
    report.setTargetUser(target);
    report.setCategory(request.category());
    report.setDescription(description);
    report.setStatus(UserReportStatus.OPEN);
    return UserReportDto.from(reportRepository.save(report), true);
  }

  @Transactional(readOnly = true)
  public PagedResponse<UserReportDto> list(
      UserReportStatus status, UserReportCategory category, Long targetUserId, int page, int size) {
    Specification<UserReport> spec = (root, query, cb) -> cb.conjunction();
    if (status != null) spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
    if (category != null)
      spec = spec.and((root, query, cb) -> cb.equal(root.get("category"), category));
    if (targetUserId != null)
      spec =
          spec.and((root, query, cb) -> cb.equal(root.get("targetUser").get("id"), targetUserId));
    var result =
        reportRepository.findAll(
            spec,
            PageRequest.of(
                Math.max(0, page),
                size < 1 || size > 100 ? 20 : size,
                Sort.by(Sort.Direction.DESC, "createdAt")));
    return PagedResponse.<UserReportDto>builder()
        .items(result.map(r -> UserReportDto.from(r, false)).getContent())
        .page(result.getNumber())
        .size(result.getSize())
        .totalItems(result.getTotalElements())
        .totalPages(result.getTotalPages())
        .hasNext(result.hasNext())
        .hasPrevious(result.hasPrevious())
        .build();
  }

  @Transactional(readOnly = true)
  public UserReportDto get(Long id) {
    return UserReportDto.from(find(id), true);
  }

  @Transactional
  public UserReportDto assign(Long id, Long adminId, User actor, HttpServletRequest request) {
    UserReport report = find(id);
    User assignee =
        adminId == null
            ? actor
            : userRepository
                .findById(adminId)
                .orElseThrow(() -> new ResourceNotFoundException("Admin not found"));
    if (assignee.getRole() != Role.ADMIN || assignee.getStatus() != UserStatus.ACTIVE) {
      throw new InvalidRequestException("Assignee must be an active admin");
    }
    report.setAssignedAdmin(assignee);
    audit(actor, AdminActionType.USER_REPORT_ASSIGNED, report.getId(), null, request);
    return UserReportDto.from(reportRepository.save(report), true);
  }

  @Transactional
  public UserReportDto changeStatus(
      Long id, UserReportStatusRequest change, User actor, HttpServletRequest request) {
    String resolutionNote = TextSanitizer.sanitize(change.resolutionNote());
    if (change.status() == UserReportStatus.OPEN) {
      throw new InvalidRequestException("Invalid status transition");
    }
    if ((change.status() == UserReportStatus.RESOLVED
            || change.status() == UserReportStatus.DISMISSED)
        && (resolutionNote == null || resolutionNote.isBlank())) {
      throw new InvalidRequestException("Resolution note required");
    }
    UserReport report = find(id);
    if (report.getStatus() == UserReportStatus.RESOLVED
        || report.getStatus() == UserReportStatus.DISMISSED) {
      throw new ResourceConflictException("USER_REPORT_CLOSED", "Report already closed");
    }
    report.setStatus(change.status());
    report.setResolutionNote(resolutionNote);
    if (change.status() != UserReportStatus.IN_REVIEW) report.setResolvedAt(LocalDateTime.now());
    audit(
        actor, AdminActionType.USER_REPORT_STATUS_CHANGED, report.getId(), resolutionNote, request);
    return UserReportDto.from(reportRepository.save(report), true);
  }

  private UserReport find(Long id) {
    return reportRepository
        .findById(id)
        .orElseThrow(() -> new ResourceNotFoundException("Report not found"));
  }

  private void audit(
      User actor, AdminActionType type, Long id, String reason, HttpServletRequest request) {
    auditRepository.save(
        AdminActionLog.builder()
            .adminUser(actor)
            .actionType(type)
            .entityType("USER_REPORT")
            .entityId(id)
            .reason(reason)
            .ipAddress(request.getRemoteAddr())
            .userAgent(request.getHeader("User-Agent"))
            .build());
  }
}
