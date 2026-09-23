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
    String resolutionNote) {
  public record Person(Long id, String publicId, String displayName) {}

  public record Target(Long id, String publicId, String slug, String displayName, String status) {}

  public static UserReportDto from(UserReport report, boolean detail) {
    User reporter = report.getReporter();
    User target = report.getTargetUser();
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
        report.getAssignedAdmin() == null ? null : report.getAssignedAdmin().getId(),
        detail ? report.getResolutionNote() : null);
  }
}
