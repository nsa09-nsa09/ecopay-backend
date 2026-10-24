package kz.hrms.splitupauth.service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import kz.hrms.splitupauth.dto.StuckMoneyItemDto;
import kz.hrms.splitupauth.entity.Payout;
import kz.hrms.splitupauth.entity.RefundTransaction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * B8: assembles the {@code GET /admin/finance/stuck} list from stuck owner payouts and participant
 * refunds. Read-only; the retry actions live on {@link PayoutService} / {@link RefundService}.
 */
@Service
@RequiredArgsConstructor
public class AdminFinanceStuckService {

  private final PayoutService payoutService;
  private final RefundService refundService;
  private final Clock clock;

  @Transactional(readOnly = true)
  public List<StuckMoneyItemDto> listStuck(String type) {
    LocalDateTime now = LocalDateTime.now(clock);
    List<StuckMoneyItemDto> items = new ArrayList<>();
    boolean wantPayout = type == null || type.isBlank() || "PAYOUT".equalsIgnoreCase(type);
    boolean wantRefund = type == null || type.isBlank() || "REFUND".equalsIgnoreCase(type);
    if (wantPayout) {
      for (Payout p : payoutService.findStuck()) {
        LocalDateTime since = firstNonNull(p.getSubmittedAt(), p.getUpdatedAt(), p.getCreatedAt());
        items.add(
            StuckMoneyItemDto.builder()
                .type("PAYOUT")
                .id(p.getId())
                .status(p.getStatus().name())
                .reason(p.getFailureReason())
                .amountKzt(p.getPayableAmount() == null ? p.getAmount() : p.getPayableAmount())
                .currency(p.getCurrency())
                .sinceAt(since)
                .minutesInStatus(minutesBetween(since, now))
                .build());
      }
    }
    if (wantRefund) {
      for (RefundTransaction r : refundService.findStuck()) {
        LocalDateTime since =
            firstNonNull(r.getProviderSubmittedAt(), r.getUpdatedAt(), r.getCreatedAt());
        items.add(
            StuckMoneyItemDto.builder()
                .type("REFUND")
                .id(r.getId())
                .status(r.getStatus().name())
                .reason(r.getLastErrorMessage())
                .amountKzt(r.getAmount())
                .currency(r.getCurrency())
                .sinceAt(since)
                .minutesInStatus(minutesBetween(since, now))
                .build());
      }
    }
    return items;
  }

  private static long minutesBetween(LocalDateTime since, LocalDateTime now) {
    if (since == null) {
      return 0;
    }
    return Math.max(0, Duration.between(since, now).toMinutes());
  }

  @SafeVarargs
  private static <T> T firstNonNull(T... values) {
    for (T v : values) {
      if (v != null) {
        return v;
      }
    }
    return null;
  }
}
