package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.UserReportCategory;

public record CreateUserReportRequest(
    @NotNull(message = "Выберите категорию жалобы") UserReportCategory category,
    @NotBlank(message = "Опишите, что произошло")
        @Size(min = 20, max = 2000, message = "Описание должно быть от 20 до 2000 символов")
        String description) {}
