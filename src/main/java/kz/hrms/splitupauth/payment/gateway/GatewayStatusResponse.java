package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class GatewayStatusResponse {
  private String externalPaymentId;

  /** "PENDING" | "SUCCESS" | "FAILED" */
  private String status;

  private String providerStatusCode;
  private String failureCode;
  private String failureMessage;
  private String cardPanMask;
  private String cardToken;

  /** Detailed provider state; {@link #status} stays the coarse PENDING/SUCCESS/FAILED view. */
  private ProviderPaymentState providerState;

  /** Amount/currency reported by the provider, used to detect mismatches before finalizing. */
  private BigDecimal amount;

  private String currency;
  private Boolean captured;
  private BigDecimal clearingAmount;

  /** Total refunded/revoked amount reported by the provider for this payment, if any. */
  private BigDecimal refundedAmount;

  /** True when the provider explicitly reports that no such operation exists. */
  private boolean notFound;
}
