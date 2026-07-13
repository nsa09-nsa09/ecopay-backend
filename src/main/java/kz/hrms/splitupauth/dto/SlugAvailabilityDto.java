package kz.hrms.splitupauth.dto;

/**
 * Response for {@code GET /api/v1/users/me/slug-available}. {@code reason} is {@code null} when
 * {@code available} is true; otherwise it's one of {@code invalid}, {@code reserved}, {@code
 * taken}.
 */
public record SlugAvailabilityDto(boolean available, String normalized, String reason) {}
