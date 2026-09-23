package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record AccountRestrictionRequest(
    @NotBlank @Size(max = 500) String reason,
    LocalDateTime startsAt,
    @NotNull LocalDateTime endsAt) {}
