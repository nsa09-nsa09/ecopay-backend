package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Tiered ECOpay commission, charged on top of each member's tariff share.
 *
 * <p>The room owner never pays a commission — only joining members do. The fee is a <b>fixed amount
 * per tier</b> of the per-member share (not a percentage):
 *
 * <pre>
 *   share ≤ 4 000  → 500
 *   4 001 – 6 000  → 700
 *   6 001 – 8 000  → 900
 *   &gt; 8 000        → 1 000
 * </pre>
 *
 * The member is charged {@code share + commission}; the owner is paid the full {@code share};
 * ECOpay keeps the {@code commission}. Thresholds and fees are configurable via {@code
 * app.commission.*} properties so the table can be tuned without a code change.
 */
@Component
public class CommissionCalculator {

  private static final int MONEY_SCALE = 2;

  @Value("${app.commission.tier1-max:4000}")
  private BigDecimal tier1Max;

  @Value("${app.commission.tier2-max:6000}")
  private BigDecimal tier2Max;

  @Value("${app.commission.tier3-max:8000}")
  private BigDecimal tier3Max;

  @Value("${app.commission.tier1-fee:500}")
  private BigDecimal tier1Fee;

  @Value("${app.commission.tier2-fee:700}")
  private BigDecimal tier2Fee;

  @Value("${app.commission.tier3-fee:900}")
  private BigDecimal tier3Fee;

  @Value("${app.commission.tier4-fee:1000}")
  private BigDecimal tier4Fee;

  /**
   * ECOpay commission for a given per-member tariff share. A non-positive or null share yields a
   * zero commission (defensive — callers validate the share first).
   */
  public BigDecimal commissionFor(BigDecimal share) {
    if (share == null || share.signum() <= 0) {
      return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    BigDecimal fee;
    if (share.compareTo(tier1Max) <= 0) {
      fee = tier1Fee;
    } else if (share.compareTo(tier2Max) <= 0) {
      fee = tier2Fee;
    } else if (share.compareTo(tier3Max) <= 0) {
      fee = tier3Fee;
    } else {
      fee = tier4Fee;
    }
    return fee.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }
}
