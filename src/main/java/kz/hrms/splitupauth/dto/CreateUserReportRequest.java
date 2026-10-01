package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.UserReportCategory;

public record CreateUserReportRequest(
    @NotNull UserReportCategory category,
    @NotBlank @Size(min = 20, max = 2000) String description) {}
