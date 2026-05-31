package kz.hrms.splitupauth.payment.gateway;

import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class PaymentGatewayRegistry {

    private final List<PaymentGateway> gateways;
    private Map<String, PaymentGateway> byName;

    /**
     * Active provider for the default flow: {@code freedompay} in prod,
     * {@code mock} in dev/test (see application*.properties).
     */
    @Value("${ecopay.payments.provider:" + FreedomPayGateway.PROVIDER_NAME + "}")
    private String activeProvider;

    public PaymentGateway resolve(String providerName) {
        if (byName == null) {
            Map<String, PaymentGateway> map = new HashMap<>();
            for (PaymentGateway g : gateways) {
                map.put(g.providerName(), g);
            }
            byName = map;
        }
        PaymentGateway gateway = byName.get(providerName == null
                ? FreedomPayGateway.PROVIDER_NAME : providerName);
        if (gateway == null) {
            throw new IllegalArgumentException("Unknown payment provider: " + providerName);
        }
        return gateway;
    }

    public PaymentGateway defaultGateway() {
        return resolve(activeProvider);
    }
}
