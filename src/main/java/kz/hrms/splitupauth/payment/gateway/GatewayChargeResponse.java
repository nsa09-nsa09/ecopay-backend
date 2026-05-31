package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayChargeResponse {
    private boolean success;
    private String externalPaymentId;
    /** URL to redirect user to (Freedom Pay's hosted checkout page). */
    private String paymentUrl;
    private boolean requiresRedirect;
    private String providerStatusCode;
    private String failureCode;
    private String failureMessage;
}
