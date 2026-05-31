package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class GatewayPayoutRequest {
    private Long payoutId;
    private String idempotencyKey;
    private String destinationCardToken;
    private BigDecimal amount;
    private String currency;
    private String description;
}
