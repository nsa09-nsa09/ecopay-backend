package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePaymentIntentRequest {

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;
}