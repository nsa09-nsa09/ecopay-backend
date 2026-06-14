package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Payload broadcast to {@code /topic/users/{userId}/account} when an admin
 * action requires the user's open session to react immediately (e.g., ban).
 * Schema is intentionally minimal — the frontend only needs to render a
 * notification and force a logout.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccountEventDto {
    private String type;
    private String reason;
    private LocalDateTime occurredAt;
}
