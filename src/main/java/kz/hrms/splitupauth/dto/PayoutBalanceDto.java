package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

/** Snapshot of an owner's payouts that are still inside the configured hold window. */
@Data
@Builder
public class PayoutBalanceDto {
  private BigDecimal heldAmount;
  private String currency;
  private long heldPayoutCount;
  private LocalDateTime nextReleaseAt;
  private LocalDateTime calculatedAt;
}
