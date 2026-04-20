package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
public class PaymentIntentResponse {
    private Long id;
    private String idempotencyKey;
    private BigDecimal amount;
    private PaymentIntentStatus status;
    private String providerName;
    private String externalPaymentId;
    private Long roomMemberId;
}