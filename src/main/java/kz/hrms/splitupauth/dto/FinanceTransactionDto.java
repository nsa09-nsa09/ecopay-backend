package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Row-level payment transaction view for the admin finance drill-down. Aggregates the "who / what /
 * when" fields the operator needs to answer a support ticket without opening the raw DB row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FinanceTransactionDto {
  private Long id;
  private LocalDateTime createdAt;
  private String type;
  private String status;
  private BigDecimal amount;
  private String currency;

  private Long roomId;
  private String roomTitle;

  private Long ownerUserId;
  private String ownerDisplayName;

  private Long payerUserId;
  private String payerDisplayName;

  private String providerName;
  private String cardPanMask;
  private String reason;
  private String failureMessage;
}
