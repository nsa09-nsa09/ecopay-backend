package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * C3: snapshot of the money-related configuration an admin needs to answer "are refunds / payouts
 * actually moving money on this env?". Values come from the already-wired {@code @Value}-injected
 * properties and {@code LiveMoneyGuard}; secrets are deliberately absent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminFinanceConfigDto {
  private String provider;
  private boolean testMode;
  private boolean liveMoneyEnabled;
  private boolean payoutDispatchEnabled;
  private boolean refundDispatchEnabled;
  private boolean postPayoutRefundEnabled;
  private boolean ownerReceivableEnabled;
  private boolean recurringEnabled;

  /** Human-readable payout hold ("3 мин" when minute-override is set, otherwise "30 дн"). */
  private String payoutHold;

  private int renewalWindowDays;
  private int renewalGraceDays;

  /** B1: last known merchant payout balance at the provider (null if never read). */
  private BigDecimal payoutBalanceKzt;

  /** When that balance was last observed (null if never read). */
  private java.time.LocalDateTime payoutBalanceCheckedAt;

  /** Owner payouts currently parked in ON_HOLD waiting for the merchant balance to recover. */
  private PayoutsWaiting payoutsWaitingForBalance;

  private Commission commission;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class PayoutsWaiting {
    private long count;
    private BigDecimal sumKzt;
  }

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class Commission {
    private BigDecimal tier1Max;
    private BigDecimal tier2Max;
    private BigDecimal tier3Max;
    private BigDecimal tier1Fee;
    private BigDecimal tier2Fee;
    private BigDecimal tier3Fee;
    private BigDecimal tier4Fee;
    private BigDecimal mixedRoomFee;
  }
}
