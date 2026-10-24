package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import kz.hrms.splitupauth.dto.AdminFinanceConfigDto;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * C3: assembles the admin-visible snapshot of live-money gates and commission tiers. Read-only;
 * never writes config — operators change these through environment variables / deployment config.
 */
@Service
public class AdminFinanceConfigService {

  private final PayoutBalanceService payoutBalanceService;

  public AdminFinanceConfigService(PayoutBalanceService payoutBalanceService) {
    this.payoutBalanceService = payoutBalanceService;
  }

  @Value("${ecopay.payments.provider:freedompay}")
  private String paymentProvider;

  @Value("${ecopay.payments.freedompay.test-mode:1}")
  private String freedomTestMode;

  @Value("${app.money.live-enabled:false}")
  private boolean liveMoneyEnabled;

  @Value("${app.money.payout-dispatch-enabled:false}")
  private boolean payoutDispatchEnabled;

  @Value("${app.money.refund-dispatch-enabled:false}")
  private boolean refundDispatchEnabled;

  @Value("${app.money.post-payout-refund-enabled:false}")
  private boolean postPayoutRefundEnabled;

  @Value("${app.money.owner-receivable-enabled:false}")
  private boolean ownerReceivableEnabled;

  @Value("${app.recurring.enabled:false}")
  private boolean recurringEnabled;

  @Value("${app.payout.hold-days:30}")
  private int payoutHoldDays;

  @Value("${app.payout.hold-minutes:0}")
  private int payoutHoldMinutes;

  @Value("${app.renewal.window-days:5}")
  private int renewalWindowDays;

  @Value("${app.renewal.grace-days:3}")
  private int renewalGraceDays;

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

  @Value("${app.commission.mixed-room-marketplace-fee:450}")
  private BigDecimal mixedRoomFee;

  public AdminFinanceConfigDto describe() {
    PayoutBalanceService.BalanceView balance = payoutBalanceService.latestKnown();
    return AdminFinanceConfigDto.builder()
        .provider(paymentProvider)
        .testMode(isTestMode())
        .liveMoneyEnabled(liveMoneyEnabled)
        .payoutDispatchEnabled(payoutDispatchEnabled)
        .refundDispatchEnabled(refundDispatchEnabled)
        .postPayoutRefundEnabled(postPayoutRefundEnabled)
        .ownerReceivableEnabled(ownerReceivableEnabled)
        .recurringEnabled(recurringEnabled)
        .payoutHold(formatHold())
        .renewalWindowDays(renewalWindowDays)
        .renewalGraceDays(renewalGraceDays)
        .payoutBalanceKzt(balance.available() ? balance.balance() : null)
        .payoutBalanceCheckedAt(balance.checkedAt())
        .payoutsWaitingForBalance(
            AdminFinanceConfigDto.PayoutsWaiting.builder()
                .count(payoutBalanceService.waitingForBalanceCount())
                .sumKzt(payoutBalanceService.waitingForBalanceSum())
                .build())
        .commission(
            AdminFinanceConfigDto.Commission.builder()
                .tier1Max(tier1Max)
                .tier2Max(tier2Max)
                .tier3Max(tier3Max)
                .tier1Fee(tier1Fee)
                .tier2Fee(tier2Fee)
                .tier3Fee(tier3Fee)
                .tier4Fee(tier4Fee)
                .mixedRoomFee(mixedRoomFee)
                .build())
        .build();
  }

  private boolean isTestMode() {
    // FreedomPay's own flag is "1"=test / "0"=live, same convention used throughout the gateway.
    return "1".equals(freedomTestMode == null ? "" : freedomTestMode.trim());
  }

  /** Minute-override wins when non-zero (QA short-circuit); else the day value. */
  private String formatHold() {
    if (payoutHoldMinutes > 0) {
      return payoutHoldMinutes + " мин";
    }
    return payoutHoldDays + " дн";
  }
}
