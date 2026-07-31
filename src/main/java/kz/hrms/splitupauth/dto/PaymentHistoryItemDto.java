package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentHistoryItemDto {
  private Long id;
  private String kind;
  private String direction;
  private String status;
  private BigDecimal amount;
  private String currency;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private Long roomId;
  private String roomTitle;
  private Long paymentIntentId;
  private Long paymentTransactionId;
  private Long refundId;
  private Long payoutId;
  private String providerName;
  private String cardPanMask;
  private String failureCode;
  private LocalDateTime releaseAt;
}
