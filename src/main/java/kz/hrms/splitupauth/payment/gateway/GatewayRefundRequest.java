package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class GatewayRefundRequest {
    private Long refundId;
    private String idempotencyKey;
    private String externalPaymentId;
    private BigDecimal amount;
    private String currency;
    private String reason;
}
