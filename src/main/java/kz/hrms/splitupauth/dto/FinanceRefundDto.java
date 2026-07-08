package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Row-level refund transaction view for the admin finance drill-down. Includes the linked
 * originating payment transaction, the room / member context, and the admin that initiated the
 * refund so the operator can trace responsibility.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceRefundDto {
  private Long id;
  private LocalDateTime createdAt;
  private String status;
  private BigDecimal amount;
  private String currency;
  private String reason;

  private Long adminUserId;
  private String adminDisplayName;

  private Long paymentTransactionId;

  private Long roomId;
  private String roomTitle;

  private Long memberUserId;
  private String memberDisplayName;

  private Long disputeId;
}
