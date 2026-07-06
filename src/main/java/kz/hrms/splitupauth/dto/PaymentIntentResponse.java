package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PaymentIntentResponse {
  private Long id;
  private String idempotencyKey;

  /** Total charged to the member = tariff share + ECOpay commission. */
  private BigDecimal amount;

  /** The member's tariff share (the portion the owner receives). */
  private BigDecimal shareAmount;

  /** The ECOpay commission added on top of the share. */
  private BigDecimal commissionAmount;

  private String currency;
  private PaymentIntentStatus status;
  private String providerName;
  private String externalPaymentId;
  private Long roomMemberId;
  private String paymentUrl;
  private Boolean requiresRedirect;
  private Boolean saveCardRequested;
  private String failureCode;
  private String failureMessage;
}
