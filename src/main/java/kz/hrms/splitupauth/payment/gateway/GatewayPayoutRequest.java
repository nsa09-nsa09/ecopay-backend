package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayPayoutRequest {
  private Long payoutId;
  private String providerOrderId;
  private String idempotencyKey;
  private String destinationUserId;
  private String destinationCardToken;
  private BigDecimal amount;
  private String currency;
  private String description;
}
