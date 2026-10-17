package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * C4: one row on the admin "overdue renewals" screen. The source is {@code RoomMember} rows whose
 * {@code renewalOverdueSince} has been stamped by {@link
 * kz.hrms.splitupauth.scheduler.RenewalReminderScheduler}; the number crunching (daysOverdue,
 * renewalAmountKzt) lives in {@code AdminOverdueRenewalService} so the DTO stays plain data.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminOverdueRenewalDto {
  private Long roomMemberId;
  private Long memberUserId;
  private String memberDisplayName;
  private String memberEmail;

  private Long roomId;
  private String roomTitle;

  private Long ownerUserId;
  private String ownerDisplayName;

  /** Last billing period's end time (same as the stored {@code nextBillingAt}). */
  private LocalDateTime periodEndAt;

  /** Days between {@link #periodEndAt} and "now", floored. */
  private long daysOverdue;

  /** Renewal cost in KZT at today's prices — amount member would pay to renew. Null on error. */
  private BigDecimal renewalAmountKzt;
}
