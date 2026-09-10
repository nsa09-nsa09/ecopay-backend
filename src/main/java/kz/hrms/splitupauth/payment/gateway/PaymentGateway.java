package kz.hrms.splitupauth.payment.gateway;

import java.util.Map;

/**
 * Provider-agnostic payment gateway interface. Implementations integrate with concrete shippers
 * like Freedom Pay, Kaspi, etc.
 */
public interface PaymentGateway {

  String providerName();

  GatewayCardBindingResponse initCardBinding(GatewayCardBindingRequest request);

  GatewayChargeResponse initCharge(GatewayChargeRequest request);

  GatewayChargeResponse chargeWithToken(GatewayChargeRequest request, String savedCardToken);

  GatewayRefundResponse refund(GatewayRefundRequest request);

  GatewayPayoutResponse payout(GatewayPayoutRequest request);

  /** True only when the provider guarantees deduplication of a replayed payout request. */
  default boolean supportsIdempotentPayoutReplay() {
    return false;
  }

  GatewayStatusResponse getStatus(String externalPaymentId);

  /**
   * Reconcile an owner payout that was accepted by the provider but has not reached a final state.
   * The merchant order id is required by Freedom Pay together with its payment id.
   */
  GatewayStatusResponse getPayoutStatus(String externalPayoutId, String merchantOrderId);

  /**
   * Verify that a webhook payload is authentic.
   *
   * @param params raw query/form parameters from the callback
   * @return parsed event if signature is valid, null otherwise
   */
  GatewayWebhookEvent verifyAndParseWebhook(Map<String, String> params);
}
