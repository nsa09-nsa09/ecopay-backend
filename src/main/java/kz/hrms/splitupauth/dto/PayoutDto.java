package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.Payout;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PayoutDto {
  private Long id;
  private BigDecimal amount;
  private String currency;
  private String status;
  private String providerPayoutId;
  private String failureReason;
  private Long roomId;
  private LocalDateTime releaseAt;
  private LocalDateTime createdAt;
  private LocalDateTime processedAt;

  /** B7: provider fee shown to the owner (meaningful in DEDUCT mode; informational in ABSORB). */
  private BigDecimal providerFeeKzt;

  /** B7: why the payout is held back (e.g. BELOW_MIN_AMOUNT), null when actively dispatching. */
  private String deferredReason;

  public static PayoutDto from(Payout p) {
    return PayoutDto.builder()
        .id(p.getId())
        .amount(p.getAmount())
        .currency(p.getCurrency())
        .status(p.getStatus() == null ? null : p.getStatus().name())
        .providerPayoutId(p.getProviderPayoutId())
        .failureReason(p.getFailureReason())
        .roomId(p.getRoom() == null ? null : p.getRoom().getId())
        .releaseAt(p.getReleaseAt())
        .createdAt(p.getCreatedAt())
        .processedAt(p.getProcessedAt())
        .providerFeeKzt(p.getProviderFeeKzt())
        .deferredReason(p.getDeferredReason())
        .build();
  }
}
