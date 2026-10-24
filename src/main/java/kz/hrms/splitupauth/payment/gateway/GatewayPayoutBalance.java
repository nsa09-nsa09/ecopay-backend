package kz.hrms.splitupauth.payment.gateway;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

/**
 * Merchant payout balance read from the provider ({@code /api/balance_status}, {@code pg_balance}).
 * {@code available=false} means the provider state could not be read (unsigned/failed response); in
 * that case {@link #balance} is null and the caller must NOT gate dispatch on it.
 */
@Data
@Builder
public class GatewayPayoutBalance {
  private boolean available;
  private BigDecimal balance;
  private String currency;
}
