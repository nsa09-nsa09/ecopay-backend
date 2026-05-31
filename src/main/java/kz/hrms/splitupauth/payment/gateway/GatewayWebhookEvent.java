package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

@Data
@Builder
public class GatewayWebhookEvent {
    /** "CHARGE" | "REFUND" | "PAYOUT" */
    private String kind;
    /** "SUCCESS" | "FAILED" | "PENDING" */
    private String resultStatus;
    private Long intentId;
    private String externalPaymentId;
    private BigDecimal amount;
    private String currency;
    private String providerStatusCode;
    private String failureCode;
    private String failureMessage;
    private String cardPanMask;
    private String cardToken;
    /** Echoed raw params used for signature verification audit trail. */
    private Map<String, String> rawParams;
    private String signature;
    /** Stable id used for inbox deduplication (e.g. pg_payment_id+pg_salt). */
    private String providerRequestId;
}
