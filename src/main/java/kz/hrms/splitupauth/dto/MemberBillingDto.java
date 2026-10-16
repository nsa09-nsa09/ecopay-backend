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

  /**
   * C2: id of the latest successful CHARGE PaymentTransaction for this membership, so the FE's
   * "запросить возврат" button can hand it to {@code POST /refund-requests}. Null when no capture
   * has succeeded yet.
   */
  private Long chargeTransactionId;

  /**
   * C2: remaining captured balance in KZT ({@code chargeAmount - active refunds}). 0 or null when
   * {@link #chargeTransactionId} is absent or already fully refunded.
   */
  private java.math.BigDecimal refundableAmountKzt;

  /**
   * C2: in-flight refund request for this participation (REQUESTED or UNDER_REVIEW); null when the
   * member has no open claim. Keeps the UI from offering a duplicate claim.
   */
  private OpenRefundRequestDto openRefundRequest;

  /** Compact view of a member's in-flight refund request. */
  @lombok.Data
  @lombok.Builder
  @lombok.NoArgsConstructor
  @lombok.AllArgsConstructor
  public static class OpenRefundRequestDto {
    private Long id;
    private kz.hrms.splitupauth.entity.RefundRequestStatus status;
    private java.time.LocalDateTime createdAt;
  }
}
