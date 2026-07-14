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
   * Real platform income = ECOpay commissions from successfully captured charges (turnover minus
   * owner payouts).
   */
  private BigDecimal platformRevenue;

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
}
