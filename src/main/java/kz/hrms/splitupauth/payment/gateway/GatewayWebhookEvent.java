package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import java.util.Map;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayWebhookEvent {
  /** "CHARGE" | "REFUND" | "PAYOUT" | "PAYOUT_CARD" */
  private String kind;

  /** "SUCCESS" | "FAILED" | "PENDING" */
  private String resultStatus;

  private Long intentId;
  private String externalPaymentId;
  private BigDecimal amount;
  private String currency;
  private String providerStatusCode;
  private String failureCode;
  private String failureMessage;
  private String cardPanMask;
  private String cardToken;

  /** Echoed raw params used for signature verification audit trail. */
  private Map<String, String> rawParams;

  private String signature;

  /** Stable id used for inbox deduplication: script + digest of the signed content minus salt. */
  private String providerRequestId;

  /** Merchant order id echoed by the provider (pg_order_id). */
  private String orderId;

  /** pg_captured from a purchase callback: false means two-step AUTHORIZED, not captured. */
  private Boolean captured;

  /** Merchant-side user id (pg_user_id) echoed by card-tokenization callbacks. */
  private String userId;
}
