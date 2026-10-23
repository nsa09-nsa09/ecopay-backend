package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundTransactionResponse {
  private Long id;
  private Long paymentTransactionId;
  private Long disputeId;
  private Long refundRequestId;
  private Long adminUserId;
  private String status;
  private BigDecimal amount;
  private String currency;
  private String reason;
  private String idempotencyKey;
  private String providerRefundId;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  /**
   * B5: days to quote the participant for the money to arrive ("поступят в течение N дней"). Only
   * meaningful while the refund is still in flight (PENDING* / PENDING_MANUAL).
   */
  private Integer expectedDays;
}
