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
