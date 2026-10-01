package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ApplyDisputeSanctionsRequest {

  @NotBlank(message = "Reason is required")
  private String reason;
}
