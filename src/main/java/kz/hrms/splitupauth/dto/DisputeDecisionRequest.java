package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.Data;

@Data
public class DisputeDecisionRequest {

  @NotBlank(message = "Status is required")
  private String status;

  @NotBlank(message = "Decision is required")
  @Size(max = 50, message = "Decision must be at most 50 characters")
  private String decision;

  @NotBlank(message = "Комментарий обязателен")
  @Size(max = 1000, message = "Комментарий должен быть не длиннее 1000 символов")
  private String comment;

  /**
   * Optional refund amount in the charge's own currency (KZT). B3: when the dispute is resolved in
   * the member's favour and the member has successful charges, this amount is refunded; {@code
   * null} means refund the full remaining balance across each still-refundable charge. Positive
   * value only.
   */
  @DecimalMin(value = "0.01", message = "Сумма возврата должна быть больше нуля")
  private BigDecimal refundAmount;
}
