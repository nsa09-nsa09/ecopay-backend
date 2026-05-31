package kz.hrms.splitupauth.payment.gateway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Deterministic in-memory payment gateway for dev/test environments.
 *
 * <p>Activated by setting {@code ecopay.payments.provider=mock} (see
 * application-dev.properties). It charges/refunds/pays out synchronously and
 * successfully, so the full membership chain (intent → SUCCESS → membership
 * PENDING → activation → owner payout) can be exercised without real Freedom Pay
 * sandbox credentials.
 *
 * <p>Charges are <b>non-redirect</b> successes, which {@code PaymentService}
 * finalizes immediately — no webhook round-trip needed.
 */
@Component
@Slf4j
public class MockPaymentGateway implements PaymentGateway {

    public static final String PROVIDER_NAME = "mock";

    private static String newId(String prefix) {
        // UUID keeps external ids unique even across app restarts (no in-memory counter
        // that resets and collides with persisted transactions).
        return prefix + UUID.randomUUID();
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public GatewayChargeResponse initCharge(GatewayChargeRequest request) {
        String ext = newId("MOCK-PAY-");
        log.info("[MOCK-GATEWAY] initCharge intent={} amount={} -> SUCCESS ({})",
                request.getIntentId(), request.getAmount(), ext);
        return GatewayChargeResponse.builder()
                .success(true)
                .requiresRedirect(false)
                .externalPaymentId(ext)
                .providerStatusCode("ok")
                .build();
    }

    @Override
    public GatewayChargeResponse chargeWithToken(GatewayChargeRequest request, String savedCardToken) {
        String ext = newId("MOCK-PAY-");
        log.info("[MOCK-GATEWAY] chargeWithToken intent={} amount={} token={} -> SUCCESS ({})",
                request.getIntentId(), request.getAmount(), savedCardToken, ext);
        return GatewayChargeResponse.builder()
                .success(true)
                .requiresRedirect(false)
                .externalPaymentId(ext)
                .providerStatusCode("ok")
                .build();
    }

    @Override
    public GatewayRefundResponse refund(GatewayRefundRequest request) {
        log.info("[MOCK-GATEWAY] refund {} amount={} -> SUCCESS", request.getExternalPaymentId(), request.getAmount());
        return GatewayRefundResponse.builder()
                .success(true)
                .externalRefundId(newId("MOCK-REF-"))
                .providerStatusCode("ok")
                .pending(false)
                .build();
    }

    @Override
    public GatewayPayoutResponse payout(GatewayPayoutRequest request) {
        log.info("[MOCK-GATEWAY] payout {} amount={} -> SUCCESS", request.getPayoutId(), request.getAmount());
        return GatewayPayoutResponse.builder()
                .success(true)
                .externalPayoutId(newId("MOCK-OUT-"))
                .providerStatusCode("ok")
                .pending(false)
                .build();
    }

    @Override
    public GatewayStatusResponse getStatus(String externalPaymentId) {
        return GatewayStatusResponse.builder()
                .externalPaymentId(externalPaymentId)
                .status("SUCCESS")
                .providerStatusCode("ok")
                .build();
    }

    @Override
    public GatewayWebhookEvent verifyAndParseWebhook(Map<String, String> params) {
        // The mock flow finalizes synchronously, so webhooks are not used.
        // Return null to signal "no verifiable event" if one ever arrives.
        return null;
    }
}
