package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class ApplyDisputeSanctionsRequest {

  @NotNull(message = "Create refund flag is required")
  private Boolean createRefund;

  private Long paymentTransactionId;

  private BigDecimal refundAmount;

  @NotBlank(message = "Reason is required")
  private String reason;
}
