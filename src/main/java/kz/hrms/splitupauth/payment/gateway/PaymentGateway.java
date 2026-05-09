package kz.hrms.splitupauth.payment.gateway;

import java.util.Map;

/**
 * Provider-agnostic payment gateway interface. Implementations integrate
 * with concrete shippers like Freedom Pay, Kaspi, etc.
 */
public interface PaymentGateway {

    String providerName();

    GatewayChargeResponse initCharge(GatewayChargeRequest request);

    GatewayChargeResponse chargeWithToken(GatewayChargeRequest request, String savedCardToken);

    GatewayRefundResponse refund(GatewayRefundRequest request);

    GatewayPayoutResponse payout(GatewayPayoutRequest request);

    GatewayStatusResponse getStatus(String externalPaymentId);

    /**
     * Verify that a webhook payload is authentic.
     * @param params raw query/form parameters from the callback
     * @return parsed event if signature is valid, null otherwise
     */
    GatewayWebhookEvent verifyAndParseWebhook(Map<String, String> params);
}
