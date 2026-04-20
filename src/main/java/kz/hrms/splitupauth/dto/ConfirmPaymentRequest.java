package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ConfirmPaymentRequest {

    @NotBlank(message = "External transaction id is required")
    private String externalTransactionId;
}