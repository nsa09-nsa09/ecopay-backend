package kz.hrms.splitupauth.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private int status;
    /**
     * Stable, machine-readable code (e.g. ACCOUNT_BANNED). Optional so legacy
     * error responses stay compatible — populated for structured cases like
     * the banned-user login response.
     */
    private String code;
    private String message;
    private LocalDateTime timestamp;
    private Map<String, String> errors;
    /** Human-readable reason for the action, surfaced to the user. */
    private String reason;
    /** When the action that caused this error occurred (e.g. ban timestamp). */
    private LocalDateTime occurredAt;

    public ErrorResponse(int status, String message) {
        this.status = status;
        this.message = message;
        this.timestamp = LocalDateTime.now();
    }

    public ErrorResponse(int status, String message, LocalDateTime timestamp, Map<String, String> errors) {
        this.status = status;
        this.message = message;
        this.timestamp = timestamp;
        this.errors = errors;
    }
}
