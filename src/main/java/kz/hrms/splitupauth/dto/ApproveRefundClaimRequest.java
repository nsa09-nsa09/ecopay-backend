package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ApproveRefundClaimRequest {

  /** Null means the full remaining captured balance. */
  @DecimalMin(value = "0.01", message = "Amount must be greater than zero")
  private BigDecimal amount;

  @NotBlank
  @Size(max = 1000)
  private String decisionNote;
}
