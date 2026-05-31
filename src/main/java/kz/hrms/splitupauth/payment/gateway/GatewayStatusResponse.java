package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayStatusResponse {
    private String externalPaymentId;
    /** "PENDING" | "SUCCESS" | "FAILED" */
    private String status;
    private String providerStatusCode;
    private String failureCode;
    private String failureMessage;
    private String cardPanMask;
    private String cardToken;
}
