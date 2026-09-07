package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

/** Provider-agnostic request to tokenize a payout card without charging it. */
@Data
@Builder
public class GatewayCardBindingRequest {
  private Long bindingId;
  private String idempotencyKey;
  private String userId;
  private String backUrl;
}
