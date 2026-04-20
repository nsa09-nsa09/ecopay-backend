package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ApplyDisputeSanctionsRequest {

    @NotNull(message = "Create refund flag is required")
    private Boolean createRefund;

    private Long paymentTransactionId;

    private BigDecimal refundAmount;

    @NotBlank(message = "Reason is required")
    private String reason;
}