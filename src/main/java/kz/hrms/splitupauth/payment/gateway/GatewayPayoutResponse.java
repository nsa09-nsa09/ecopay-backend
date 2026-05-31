package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayPayoutResponse {
    private boolean success;
    private String externalPayoutId;
    private String providerStatusCode;
    private String failureCode;
    private String failureMessage;
    private boolean pending;
}
