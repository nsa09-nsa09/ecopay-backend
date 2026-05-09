package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayRefundResponse {
    private boolean success;
    private String externalRefundId;
    private String providerStatusCode;
    private String failureCode;
    private String failureMessage;
    /** True if the provider acknowledged but the refund is still being processed asynchronously. */
    private boolean pending;
}
