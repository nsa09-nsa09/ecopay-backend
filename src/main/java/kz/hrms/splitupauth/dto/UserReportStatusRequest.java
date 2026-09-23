package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.UserReportStatus;

public record UserReportStatusRequest(
    @NotNull UserReportStatus status, @Size(max = 2000) String resolutionNote) {}
