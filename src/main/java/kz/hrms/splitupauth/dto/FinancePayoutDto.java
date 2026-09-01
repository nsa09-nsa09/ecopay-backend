package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Row-level owner payout view for the admin finance drill-down. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinancePayoutDto {
  private Long id;
  private LocalDateTime createdAt;
  private LocalDateTime releaseAt;
  private LocalDateTime processedAt;
  private LocalDateTime nextRetryAt;
  private String status;
  private BigDecimal amount;
  private String currency;

  private Long roomId;
  private String roomTitle;

  private Long ownerUserId;
  private String ownerDisplayName;

  private Long triggeringPaymentIntentId;
  private Long payoutMethodId;
  private String payoutMethodPanMask;
  private String providerName;
  private String providerPayoutId;
  private String failureReason;
  private Integer retryCount;
}
