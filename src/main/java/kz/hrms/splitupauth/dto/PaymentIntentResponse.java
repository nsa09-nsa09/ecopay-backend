package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentIntentResponse {
  private Long id;
  private String idempotencyKey;

  /** Total charged to the member = tariff share + EcoPay commission. */
  private BigDecimal amount;

  /** The member's tariff share (the portion the owner receives). */
  private BigDecimal shareAmount;
  private BigDecimal shareKzt;

  /** The EcoPay commission added on top of the share. */
  private BigDecimal commissionAmount;
  private BigDecimal commissionKzt;
  private BigDecimal payableTotalKzt;

  private String currency;
  private String settlementCurrency;
  private BigDecimal originalPrice;
  private String originalCurrency;
  private PaymentIntentStatus status;
  private String providerName;
  private String externalPaymentId;
  private Long roomMemberId;
  private String paymentUrl;
  private Boolean requiresRedirect;
  private Boolean saveCardRequested;
  private LocalDateTime expiresAt;
  private Boolean compensationRequired;
  private Boolean reviewRequired;
  private String reviewReason;
  private String failureCode;
  private String failureMessage;
}
