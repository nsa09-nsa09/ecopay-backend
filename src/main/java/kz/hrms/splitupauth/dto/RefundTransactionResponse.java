package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundTransactionResponse {
    private Long id;
    private Long paymentTransactionId;
    private Long disputeId;
    private Long adminUserId;
    private String status;
    private BigDecimal amount;
    private String currency;
    private String reason;
    private String idempotencyKey;
    private String providerRefundId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}