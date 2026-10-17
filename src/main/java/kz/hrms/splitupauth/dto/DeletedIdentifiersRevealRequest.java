package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DeletedIdentifiersRevealRequest(
    @NotNull @AssertTrue Boolean confirmed, @NotBlank @Size(max = 500) String reason) {}
