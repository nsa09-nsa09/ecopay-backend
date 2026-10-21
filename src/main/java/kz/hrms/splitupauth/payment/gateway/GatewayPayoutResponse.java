package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayPayoutResponse {
  private boolean success;
  private String externalPayoutId;
  private String providerStatusCode;
  private String failureCode;
  private String failureMessage;
  private boolean pending;

  /** Provider's remaining payout balance, returned in the reg2reg response ({@code pg_balance}). */
  private BigDecimal providerBalance;
}
