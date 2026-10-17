package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserReport;
import kz.hrms.splitupauth.entity.UserReportCategory;
import kz.hrms.splitupauth.entity.UserReportStatus;

public record UserReportDto(
    Long id,
    UserReportCategory category,
    UserReportStatus status,
    Person reporter,
    Target target,
    String description,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LocalDateTime resolvedAt,
    Long assignedAdminId,
    /** B12: full admin info so the UI can show the assignee's name without a second lookup. */
    AssignedAdmin assignedAdmin,
    String resolutionNote) {
  public record Person(Long id, String publicId, String displayName) {}

  public record Target(Long id, String publicId, String slug, String displayName, String status) {}

  public record AssignedAdmin(Long id, String displayName) {}

  public static UserReportDto from(UserReport report, boolean detail) {
    User reporter = report.getReporter();
    User target = report.getTargetUser();
    User admin = report.getAssignedAdmin();
    return new UserReportDto(
        report.getId(),
        report.getCategory(),
        report.getStatus(),
        new Person(reporter.getId(), reporter.getPublicId(), reporter.getDisplayName()),
        new Target(
            target.getId(),
            target.getPublicId(),
            target.getSlug(),
            target.getDisplayName(),
            target.getStatus().name()),
        detail ? report.getDescription() : null,
        report.getCreatedAt(),
        report.getUpdatedAt(),
        report.getResolvedAt(),
        admin == null ? null : admin.getId(),
        admin == null ? null : new AssignedAdmin(admin.getId(), admin.getDisplayName()),
        detail ? report.getResolutionNote() : null);
  }
}
