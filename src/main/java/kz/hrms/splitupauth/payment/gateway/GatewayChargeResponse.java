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
   * True only when the gateway PROVES the money is captured synchronously (the in-memory mock). A
   * provider "ok" acknowledgement of a token/recurring charge is acceptance, not capture: such
   * charges stay open until a signed callback or a status query confirms them.
   */
  private boolean captureConfirmed;

  /**
   * Acquiring cost charged by the provider for this capture, when the provider reports it (Block
   * 5d). Null when unknown. Carried onto the payment intent/transaction for net-revenue reporting.
   */
  private BigDecimal providerFeeAmount;
}
