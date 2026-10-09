package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AdminDashboardKpisDto {
  private long totalUsers;
  private long activeUsers;
  private long bannedUsers;

  /** Accounts whose status = DELETED (anonymized via account deletion). */
  private long deletedUsers;

  /** New users in the trailing 30 days, based on users.created_at. */
  private long newUsersLast30Days;

  private long totalRooms;
  private long openRooms;
  private long activeRooms;
  private long completedRooms;
  private long blockedRooms;
  private BigDecimal totalRevenue;

  /**
   * Real platform income = EcoPay commissions from successfully captured charges (turnover minus
   * owner payouts).
   */
  private BigDecimal platformRevenue;

  /**
   * Total acquiring cost paid to the payment provider on those same charges (Block 5d). Additive
   * and optional: it only counts transactions that reported a fee, so historically it is 0.
   */
  private BigDecimal providerFeeTotal;

  /**
   * Net platform income = {@link #platformRevenue} − {@link #providerFeeTotal}. The honest margin
   * once acquiring cost is accounted for. Equals platformRevenue when no fees are recorded.
   */
  private BigDecimal netRevenue;

  private BigDecimal totalRefunds;
  private long openDisputes;
  private long pendingModeration;
  private long pendingPayouts;

  // ----- Visitor analytics (populated from site_visit, see V26). -----
  private long uniqueVisitorsToday;
  private long uniqueVisitors30d;
  private long totalPageViews30d;

  // ----- Membership rollups. -----
  /** Active (status=ACTIVE) members across all rooms; gives "people in flight" count. */
  private long totalActiveMembersAcrossRooms;

  /** Average ACTIVE members per non-deleted room. 0 if no rooms. */
  private double avgMembersPerRoom;

  /** Sum of pricePerMember * activeMembers in KZT (denormalized via fx snapshot). */
  private BigDecimal totalActiveSubscriptionsValueKzt;

  /** Rooms (non-deleted) created in the trailing 30 days. */
  private long newRoomsLast30Days;

  // ----- Funnel + quality. -----
  /** registrations30d / uniqueVisitors30d, safe at zero. Expressed in percent (0..100). */
  private double conversionVisitorToUser30d;

  /** totalRefunds / totalRevenue * 100, safe at zero. */
  private double refundRatePercent;

  /** Support tickets currently OPEN/IN_PROGRESS/WAITING_USER/ESCALATED. */
  private long openTickets;

  /** Mean current occupancy ratio across non-deleted rooms (occupied / max_members). */
  private double avgRoomFillRate;

  // ----- Startup metrics (additive, OPTIONAL). null = not computable / not available; never a
  // substituted zero. Percentages are 0..100. -----

  /** Distinct authenticated users seen (site_visit.user_id) today / last 7 / last 30 days. */
  private Long dau;

  private Long wau;
  private Long mau;

  /** dau / mau * 100. */
  private Double dauMauPercent;

  private Long registrations7d;
  private Long registrations30d;

  /** Users whose FIRST successful charge happened in the last 30 days. */
  private Long usersWithFirstSuccessfulPayment30d;

  /** Of users registered in the last 30 days, the share that already paid successfully. */
  private Double signupToFirstPaymentConversion30d;

  /** Captured member charges (incl. later refunded ones) in the last 30 days. */
  private Long successfulPayments30d;

  /** Outcome mix of payment intents created in the last 30 days. */
  private Double paymentSuccessRate30d;

  private Double paymentFailureRate30d;
  private Double paymentPendingRate30d;

  /** Intents needing a human: REQUIRES_REVIEW / CAPTURE_ANOMALY / review flag. */
  private Long paymentRequiresReviewCount;

  /** Owner payout reserve still inside its hold window (EcoPay-internal, not a card hold). */
  private BigDecimal payoutHeldAmountKzt;

  /** Payouts whose hold elapsed and that wait for dispatch. */
  private Long payoutDueCount;

  private Long payoutPendingProviderCount;
  private Long payoutRequiresReviewCount;
  private Long refundPendingProviderCount;
  private Long refundRequiresReviewCount;
  private Long freedomWebhookDeadLetterCount;

  /** Copies the optional startup metrics from {@code extra} onto this DTO. */
  public AdminDashboardKpisDto withStartupMetrics(AdminDashboardKpisDto extra) {
    this.dau = extra.dau;
    this.wau = extra.wau;
    this.mau = extra.mau;
    this.dauMauPercent = extra.dauMauPercent;
    this.registrations7d = extra.registrations7d;
    this.registrations30d = extra.registrations30d;
    this.usersWithFirstSuccessfulPayment30d = extra.usersWithFirstSuccessfulPayment30d;
    this.signupToFirstPaymentConversion30d = extra.signupToFirstPaymentConversion30d;
    this.successfulPayments30d = extra.successfulPayments30d;
    this.paymentSuccessRate30d = extra.paymentSuccessRate30d;
    this.paymentFailureRate30d = extra.paymentFailureRate30d;
    this.paymentPendingRate30d = extra.paymentPendingRate30d;
    this.paymentRequiresReviewCount = extra.paymentRequiresReviewCount;
    this.payoutHeldAmountKzt = extra.payoutHeldAmountKzt;
    this.payoutDueCount = extra.payoutDueCount;
    this.payoutPendingProviderCount = extra.payoutPendingProviderCount;
    this.payoutRequiresReviewCount = extra.payoutRequiresReviewCount;
    this.refundPendingProviderCount = extra.refundPendingProviderCount;
    this.refundRequiresReviewCount = extra.refundRequiresReviewCount;
    this.freedomWebhookDeadLetterCount = extra.freedomWebhookDeadLetterCount;
    return this;
  }
}
