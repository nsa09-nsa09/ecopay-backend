package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Public-facing view of a user, exposed at {@code GET /api/v1/users/public/{publicId}}.
 * Intentionally excludes PII (email, phone, role) — only the data needed to
 * render a profile card and link to peer reviews.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicProfileDto {
    private Long id;
    private String publicId;
    private String displayName;
    private String avatar;
    private Integer reputation;
    private UserStatus status;
    private Double averageRating;
    private Long reviewsCount;
    private Long completedRoomsCount;
    private LocalDateTime createdAt;
}
