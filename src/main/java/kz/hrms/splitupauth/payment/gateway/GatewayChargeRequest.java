package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayChargeRequest {
  private Long intentId;
  private Long roomMemberId;
  private Long roomId;
  private String idempotencyKey;
  private BigDecimal amount;
  private String currency;
  private String description;
  private String userEmail;
  private String userPhone;

  /** Merchant-side user id (pg_user_id). Required by Freedom Pay when saving a card. */
  private String userId;

  private boolean saveCardRequested;

  /** Optional URL the gateway should redirect to on success/failure. */
  private String successUrl;

  private String failureUrl;

  /**
   * Optional override for the provider order id. Used by non-room flows (e.g. payout-card binding)
   * so their order id is NOT a bare numeric PaymentIntent id — keeps their async webhooks from
   * being mis-matched to a real payment intent.
   */
  private String orderIdOverride;
}
