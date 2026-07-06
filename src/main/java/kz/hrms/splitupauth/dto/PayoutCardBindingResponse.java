package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

/** Result of starting a payout-card binding: where to send the owner to enter their card. */
@Data
@Builder
public class PayoutCardBindingResponse {
  private Long bindingId;
  private String paymentUrl;
  private boolean requiresRedirect;
  private String status; // PENDING | FAILED
  private String failureMessage;
}
