package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Body for a manual renewal payment: just the client-chosen idempotency key. */
@Data
public class RenewalIntentRequest {

  @NotBlank(message = "Idempotency key is required")
  private String idempotencyKey;
}
