package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class GatewayChargeRequest {
    private Long intentId;
    private String idempotencyKey;
    private BigDecimal amount;
    private String currency;
    private String description;
    private String userEmail;
    private String userPhone;
    private boolean saveCardRequested;
    /** Optional URL the gateway should redirect to on success/failure. */
    private String successUrl;
    private String failureUrl;
}
