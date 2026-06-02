package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Admin sets/clears a user's "verified owner" flag. */
@Data
public class SetOwnerVerifiedRequest {
    @NotNull(message = "verified is required")
    private Boolean verified;
    private String reason;
}
