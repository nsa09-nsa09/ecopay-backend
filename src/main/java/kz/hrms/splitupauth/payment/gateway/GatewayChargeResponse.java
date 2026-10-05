package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayChargeResponse {
  private boolean success;
  private String externalPaymentId;

  /** URL to redirect user to (Freedom Pay's hosted checkout page). */
  private String paymentUrl;

  private boolean requiresRedirect;
  private String providerStatusCode;
  private String failureCode;
  private String failureMessage;

  /**
   * Acquiring cost charged by the provider for this capture, when the provider reports it (Block
   * 5d). Null when unknown. Carried onto the payment intent/transaction for net-revenue reporting.
   */
  private BigDecimal providerFeeAmount;
}
