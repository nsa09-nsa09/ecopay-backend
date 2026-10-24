package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.PayoutBalanceSnapshot;
import kz.hrms.splitupauth.entity.PayoutStatus;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutBalance;
import kz.hrms.splitupauth.payment.gateway.PaymentGatewayRegistry;
import kz.hrms.splitupauth.repository.PayoutBalanceSnapshotRepository;
import kz.hrms.splitupauth.repository.PayoutRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B1: owns everything about the merchant payout balance — the cached {@code /api/balance_status}
 * read used to gate dispatch, the snapshot trail, and the one-per-6h admin alert when the balance
 * runs short. Kept out of {@link PayoutService} so the dispatch logic stays about individual
 * payouts, and so the 60-second cache has a single owner (the dispatcher runs under a cluster lock,
 * so a plain in-memory cache is enough).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PayoutBalanceService {

  private static final long CACHE_TTL_SECONDS = 60;
  private static final long ADMIN_ALERT_BUCKET_HOURS = 6;

  private final PaymentGatewayRegistry gatewayRegistry;
  private final PayoutBalanceSnapshotRepository snapshotRepository;
  private final PayoutRepository payoutRepository;
  private final NotificationService notificationService;
  private final Clock clock;

  /** Headroom kept untouched so a race between the balance read and dispatch cannot overdraw. */
  @Value("${app.payout.balance-reserve-kzt:0}")
  private BigDecimal balanceReserve;

  private volatile BigDecimal cachedBalance;
  private volatile LocalDateTime cachedAt;
  private volatile boolean cachedAvailable;

  /** Snapshot for the admin finance config card. */
  public record BalanceView(boolean available, BigDecimal balance, LocalDateTime checkedAt) {}

  /**
   * Cached merchant balance (refreshed at most every {@value #CACHE_TTL_SECONDS}s). A fresh read is
   * persisted as a snapshot. When the provider cannot be read, the result is {@code
   * available=false} and the caller must not gate on it.
   */
  public BalanceView currentBalance() {
    LocalDateTime now = LocalDateTime.now(clock);
    if (cachedAt != null && cachedAt.plusSeconds(CACHE_TTL_SECONDS).isAfter(now)) {
      return new BalanceView(cachedAvailable, cachedBalance, cachedAt);
    }
    GatewayPayoutBalance balance;
    try {
      balance = gatewayRegistry.defaultGateway().getPayoutBalance();
    } catch (Exception ex) {
      log.warn("Payout balance read failed, not gating dispatch: {}", ex.getMessage());
      cachedAvailable = false;
      cachedBalance = null;
      cachedAt = now;
      return new BalanceView(false, null, now);
    }
    cachedAvailable = balance != null && balance.isAvailable() && balance.getBalance() != null;
    cachedBalance = cachedAvailable ? balance.getBalance() : null;
    cachedAt = now;
    if (cachedAvailable) {
      saveSnapshot(cachedBalance, "BALANCE_CHECK");
    }
    return new BalanceView(cachedAvailable, cachedBalance, now);
  }

  /**
   * Amount that may be dispatched this tick ({@code balance − reserve}), or {@code null} when the
   * balance is unknown (never gate on an unknown balance). Primes the 60s cache.
   */
  public BigDecimal availableForDispatch() {
    BalanceView view = currentBalance();
    if (!view.available() || view.balance() == null) {
      return null;
    }
    BigDecimal reserve = balanceReserve == null ? BigDecimal.ZERO : balanceReserve;
    return view.balance().subtract(reserve).max(BigDecimal.ZERO);
  }

  /** Records {@code pg_balance} returned by a reg2reg payout. No-op when the provider sent none. */
  @Transactional
  public void recordReg2regBalance(BigDecimal balance) {
    if (balance == null) {
      return;
    }
    saveSnapshot(balance, "REG2REG");
    cachedBalance = balance;
    cachedAvailable = true;
    cachedAt = LocalDateTime.now(clock);
  }

  /**
   * Alerts every admin that the balance is short, at most once per {@value
   * #ADMIN_ALERT_BUCKET_HOURS} hours (the bucket id dedups via {@code notifyAdminsOnce}).
   */
  public void alertBalanceLow() {
    long bucket = LocalDateTime.now(clock).toEpochSecond(java.time.ZoneOffset.UTC) / 3600;
    long bucketId = bucket / ADMIN_ALERT_BUCKET_HOURS;
    notificationService.notifyAdminsOnce(
        NotificationType.PAYOUT_BALANCE_LOW,
        bucketId,
        java.util.Map.of(),
        "/admin/finance",
        java.util.Map.of());
  }

  public BalanceView latestKnown() {
    if (cachedAt != null) {
      return new BalanceView(cachedAvailable, cachedBalance, cachedAt);
    }
    return snapshotRepository
        .findTopByOrderByCreatedAtDesc()
        .map(s -> new BalanceView(true, s.getBalance(), s.getCreatedAt()))
        .orElse(new BalanceView(false, null, null));
  }

  public long waitingForBalanceCount() {
    return payoutRepository.countByStatus(PayoutStatus.ON_HOLD);
  }

  public BigDecimal waitingForBalanceSum() {
    BigDecimal sum = payoutRepository.sumPayableByStatus(PayoutStatus.ON_HOLD);
    return sum == null ? BigDecimal.ZERO : sum;
  }

  private void saveSnapshot(BigDecimal balance, String source) {
    try {
      snapshotRepository.save(
          PayoutBalanceSnapshot.builder().balance(balance).source(source).build());
    } catch (Exception ex) {
      log.warn("Failed to persist payout balance snapshot ({}): {}", source, ex.getMessage());
    }
  }
}
