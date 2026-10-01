package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.RefundReasonCode;
import lombok.Data;

@Data
public class CreateRefundClaimRequest {

  @NotNull private Long paymentTransactionId;

  @NotNull private RefundReasonCode reasonCode;

  @NotBlank
  @Size(max = 1000)
  private String description;

  @NotBlank
  @Size(max = 100)
  private String idempotencyKey;
}
