package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.Payout;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PayoutDto {
    private Long id;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String providerPayoutId;
    private String failureReason;
    private Long roomId;
    private LocalDateTime createdAt;
    private LocalDateTime processedAt;

    public static PayoutDto from(Payout p) {
        return PayoutDto.builder()
                .id(p.getId())
                .amount(p.getAmount())
                .currency(p.getCurrency())
                .status(p.getStatus())
                .providerPayoutId(p.getProviderPayoutId())
                .failureReason(p.getFailureReason())
                .roomId(p.getRoom() == null ? null : p.getRoom().getId())
                .createdAt(p.getCreatedAt())
                .processedAt(p.getProcessedAt())
                .build();
    }
}
