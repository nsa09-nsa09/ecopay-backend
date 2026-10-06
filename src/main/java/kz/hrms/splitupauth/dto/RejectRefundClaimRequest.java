package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RejectRefundClaimRequest {

  @NotBlank
  @Size(max = 1000)
  private String decisionNote;
}
