package kz.hrms.splitupauth.payment.gateway;

import lombok.Builder;
import lombok.Data;

/** Result of starting a zero-amount payout-card tokenization flow. */
@Data
@Builder
public class GatewayCardBindingResponse {
  private boolean success;
  private String externalBindingId;
  private String redirectUrl;
  private boolean requiresRedirect;
  private String providerStatusCode;
  private String failureCode;
  private String failureMessage;

  /** Present for gateways that complete tokenization synchronously (for example, the mock). */
  private String cardToken;

  private String cardPanMask;
}
