package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayRefundResponse {
  private boolean success;
  private String externalRefundId;
  private String providerStatusCode;
  private String failureCode;
  private String failureMessage;

  /** True if the provider acknowledged but the refund is still being processed asynchronously. */
  private boolean pending;

  /**
   * Total amount the provider had already refunded for the parent charge BEFORE this revoke/cancel
   * was sent (read from the pre-submit status query). Null when the provider state was not read
   * (e.g. a non-FreedomPay gateway). Used to confirm this refund precisely once the provider's
   * refunded total grows by this refund's amount.
   */
  private BigDecimal providerRefundedBefore;
}
