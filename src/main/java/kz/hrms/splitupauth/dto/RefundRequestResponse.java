package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.RefundReasonCode;
import kz.hrms.splitupauth.entity.RefundRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequestResponse {
  private Long id;
  private Long paymentTransactionId;
  private RefundReasonCode reasonCode;
  private String description;
  private RefundRequestStatus status;
  private BigDecimal approvedAmount;
  private String decisionNote;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
  private LocalDateTime decidedAt;

  /** C2: room this charge belongs to, so the admin screen can jump to its page. */
  private Long roomId;

  private String roomTitle;

  /** C2: participation (room_members.id) under dispute. */
  private Long roomMemberId;

  /** C2: who filed the claim. */
  private RequesterRef requester;

  /** C2: original captured amount of the disputed charge. */
  private BigDecimal chargedAmountKzt;

  /** C2: already-refunded (active + success) amount for the same charge. */
  private BigDecimal refundedAmountKzt;

  /** C2: {@code chargedAmountKzt - refundedAmountKzt}. */
  private BigDecimal refundableAmountKzt;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class RequesterRef {
    private Long id;
    private String displayName;
    private String publicId;
  }
}
