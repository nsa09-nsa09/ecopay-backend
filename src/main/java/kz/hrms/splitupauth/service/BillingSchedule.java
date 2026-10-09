package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomMember;

/**
 * The billing-period arithmetic for a membership, extracted so the initial payment, manual renewal
 * and auto-renewal all compute the same schedule. Supports {@code MONTHLY} (+1 month) and {@code
 * YEARLY} (+1 year); {@code OTHER} has no recurring period and is reported as unsupported.
 *
 * <p>Package-private: only the payment services drive the schedule.
 */
final class BillingSchedule {

  private BillingSchedule() {}

  /** Whether a renewal/billing period can be computed for this period type. */
  static boolean isSupported(PeriodType type) {
    return type == PeriodType.MONTHLY || type == PeriodType.YEARLY;
  }

  /** The instant one period after {@code from}. Throws for an unsupported period type. */
  static LocalDateTime nextAfter(LocalDateTime from, PeriodType type) {
    return switch (type) {
      case MONTHLY -> from.plusMonths(1);
      case YEARLY -> from.plusYears(1);
      case OTHER -> throw new IllegalArgumentException("OTHER period has no billing schedule");
    };
  }

  /**
   * Initializes the schedule on a member that has none yet, from {@code anchor} (the first captured
   * payment time): {@code billingAnchorAt = billingPeriodStart = anchor}, {@code nextBillingAt =
   * anchor + one period}. Idempotent — any field already set is left untouched. No-op for an
   * unsupported period type.
   */
  static void initialize(RoomMember member, LocalDateTime anchor, PeriodType type) {
    if (!isSupported(type) || anchor == null) {
      return;
    }
    if (member.getBillingAnchorAt() == null) {
      member.setBillingAnchorAt(anchor);
    }
    if (member.getBillingPeriodStart() == null) {
      member.setBillingPeriodStart(member.getBillingAnchorAt());
    }
    if (member.getNextBillingAt() == null) {
      member.setNextBillingAt(nextAfter(member.getBillingPeriodStart(), type));
    }
    if (member.getRecurringRetryCount() == null) {
      member.setRecurringRetryCount(0);
    }
  }

  /**
   * Advances the member exactly one period: the period that was {@code nextBillingAt} becomes the
   * current period start and {@code nextBillingAt} moves forward one period; retry bookkeeping is
   * reset. Caller guarantees this runs once per paid period.
   */
  static void advance(RoomMember member, PeriodType type) {
    LocalDateTime current = member.getNextBillingAt();
    member.setBillingPeriodStart(current);
    member.setNextBillingAt(nextAfter(current, type));
    member.setRecurringRetryCount(0);
    member.setRecurringNextRetryAt(null);
  }
}
