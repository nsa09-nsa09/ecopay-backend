package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

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
    private BigDecimal totalRefunds;
    private long openDisputes;
    private long pendingModeration;
    private long pendingPayouts;
}
