package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Renewal/billing state for an ACTIVE membership, surfaced on {@code GET /rooms/{id}/members/me}.
 * Null on the parent DTO for non-ACTIVE members.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberBillingDto {

  /** When the current period ends / the next one is due; null when not yet determined. */
  private LocalDateTime nextBillingAt;

  /** True when the next period can be paid right now (renewal window open and not yet paid). */
  private boolean renewalOpen;

  /** share + commission for the next period; null when {@link #renewalOpen} is false. */
  private BigDecimal renewalAmountKzt;

  private BigDecimal renewalShareKzt;
  private BigDecimal renewalCommissionKzt;

  /** The period has expired with no payment and the grace window is running. */
  private boolean overdue;
}
