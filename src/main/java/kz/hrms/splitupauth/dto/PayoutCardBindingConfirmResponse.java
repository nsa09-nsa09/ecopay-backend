package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

/** Result of confirming a payout-card binding after the owner returns from the hosted page. */
@Data
@Builder
public class PayoutCardBindingConfirmResponse {
  private String status; // SUCCESS | PENDING | FAILED
  private PayoutMethodDto method; // present when SUCCESS
  private String message;
}
