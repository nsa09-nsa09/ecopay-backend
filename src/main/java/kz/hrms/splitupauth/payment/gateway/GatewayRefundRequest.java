package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayRefundRequest {
  private Long refundId;
  private String idempotencyKey;
  private String externalPaymentId;
  private BigDecimal amount;
  private String currency;
  private String reason;

  /**
   * B6: fiscal receipt positions for /revoke (pg_receipt_positions[]); null/empty when disabled.
   */
  private java.util.List<kz.hrms.splitupauth.payment.fiscal.FiscalReceiptBuilder.ReceiptPosition>
      receiptPositions;
}
