package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Personal analytics surface for the "my dashboard" page. Aggregates a user's memberships, spend
 * and reputation into a single payload so the SPA renders the whole screen in one request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberDashboardDto {

  // ----- Membership rollups -----
  private long joinedRoomsActive;
  private long joinedRoomsCompleted;
  private long totalRoomsJoined;

  // ----- Money (everything in KZT) -----
  /** Recurring spend per period if all current ACTIVE memberships keep running. */
  private BigDecimal monthlySpendKzt;

  /** Lifetime sum of successful CHARGE transactions this user has made. */
  private BigDecimal totalSpentKzt;

  /**
   * Estimated savings: across all ACTIVE memberships, the gap between solo pricing (priceTotal) and
   * the user's share (pricePerMember). Approximate — relies on the room owner reporting truthful
   * priceTotal at creation.
   */
  private BigDecimal totalSavedKzt;

  /** Earliest upcoming billing date across the user's active rooms, if known. */
  private LocalDateTime nextPaymentDate;

  /** Amount that will be charged at {@link #nextPaymentDate}, in KZT. */
  private BigDecimal nextPaymentAmountKzt;

  // ----- Reputation -----
  private Integer reputationScore;
  private long reviewsReceived;
  private long disputesAsMember;

  /** Most recent 5 RoomEventLog rows where this user was the actor. */
  private List<RoomEventLogDto> recentEvents;
}
