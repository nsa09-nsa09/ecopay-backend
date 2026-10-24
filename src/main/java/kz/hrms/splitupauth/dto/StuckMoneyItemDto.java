package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B8: one stuck owner payout or participant refund surfaced on {@code GET /admin/finance/stuck}, so
 * an operator can see what is wedged, why, and for how long, then act via the retry endpoints.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StuckMoneyItemDto {
  /** PAYOUT or REFUND. */
  private String type;

  private Long id;
  private String status;

  /** Last provider/failure reason retained for the operator. */
  private String reason;

  private BigDecimal amountKzt;
  private String currency;

  /** When the row entered its current/holding state (best available timestamp). */
  private LocalDateTime sinceAt;

  /** Minutes elapsed since {@link #sinceAt}. */
  private long minutesInStatus;
}
