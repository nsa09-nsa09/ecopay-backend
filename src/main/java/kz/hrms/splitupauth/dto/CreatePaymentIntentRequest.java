package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CreatePaymentIntentRequest {

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    /** If true, ask Freedom Pay to tokenize the card for future recurring charges. */
    private Boolean saveCard;

    /** If set, charge this previously saved card directly (no redirect). */
    private Long savedCardId;
}
