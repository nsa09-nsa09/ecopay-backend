package kz.hrms.splitupauth.service;

import jakarta.persistence.EntityManager;
import kz.hrms.splitupauth.dto.AdminDashboardKpisDto;
import kz.hrms.splitupauth.dto.DashboardMetricPointDto;
import kz.hrms.splitupauth.dto.DashboardMetricsDto;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final EntityManager em;
    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public AdminDashboardKpisDto getKpis() {
        long totalUsers = singleLong("SELECT COUNT(u) FROM User u");
        long activeUsers = singleLong(
                "SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.ACTIVE);
        long bannedUsers = singleLong(
                "SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.BANNED);
        long deletedUsers = singleLong(
                "SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.DELETED);
        long newUsersLast30Days = singleLong(
                "SELECT COUNT(u) FROM User u WHERE u.createdAt >= ?1",
                LocalDateTime.now().minusDays(30));

        long totalRooms = singleLong("SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL");
        long openRooms = singleLong(
                "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1", RoomStatus.OPEN);
        long activeRooms = singleLong(
                "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1", RoomStatus.ACTIVE);
        long completedRooms = singleLong(
                "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1", RoomStatus.COMPLETED);
        long blockedRooms = singleLong(
                "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1", RoomStatus.BLOCKED);

        BigDecimal totalRevenue = singleBigDecimal(
                "SELECT COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t "
                        + "WHERE t.type = ?1 AND t.status IN (?2, ?3, ?4)",
                PaymentTransactionType.CHARGE,
                PaymentTransactionStatus.SUCCESS,
                PaymentTransactionStatus.REFUNDED_PARTIAL,
                PaymentTransactionStatus.REFUNDED_FULL);
        BigDecimal totalRefunds = singleBigDecimal(
                "SELECT COALESCE(SUM(r.amount), 0) FROM RefundTransaction r WHERE r.status = ?1",
                RefundStatus.SUCCESS);

        long openDisputes = singleLong(
                "SELECT COUNT(d) FROM Dispute d WHERE d.status = kz.hrms.splitupauth.entity.DisputeStatus.OPEN");
        long pendingModeration = singleLong(
                "SELECT COUNT(m) FROM ModerationQueue m WHERE m.status IN "
                        + "(kz.hrms.splitupauth.entity.ModerationQueueStatus.OPEN, "
                        + "kz.hrms.splitupauth.entity.ModerationQueueStatus.IN_REVIEW)");
        long pendingPayouts = singleLong(
                "SELECT COUNT(p) FROM Payout p WHERE p.status IN ('PENDING','PENDING_METHOD','PROCESSING')");

        return AdminDashboardKpisDto.builder()
                .totalUsers(totalUsers)
                .activeUsers(activeUsers)
                .bannedUsers(bannedUsers)
                .deletedUsers(deletedUsers)
                .newUsersLast30Days(newUsersLast30Days)
                .totalRooms(totalRooms)
                .openRooms(openRooms)
                .activeRooms(activeRooms)
                .completedRooms(completedRooms)
                .blockedRooms(blockedRooms)
                .totalRevenue(totalRevenue)
                .totalRefunds(totalRefunds)
                .openDisputes(openDisputes)
                .pendingModeration(pendingModeration)
                .pendingPayouts(pendingPayouts)
                .build();
    }

    /**
     * Time-series for the admin dashboard chart. registrations counts rows in
     * users (by created_at); logins counts successful login_attempts in the
     * same bucket. SQL is grouped via date_trunc to keep this O(distinct buckets)
     * instead of pulling all rows into Java.
     */
    @Transactional(readOnly = true)
    public DashboardMetricsDto getMetrics(String granularity, LocalDate from, LocalDate to) {
        String unit = normalizeGranularity(granularity);
        // The controller binds calendar dates (yyyy-MM-dd). Widen `from` to the
        // start of the day and `to` to the end of the day so a single-day filter
        // ("from=to") still includes rows recorded later that same day.
        LocalDateTime resolvedTo = (to != null) ? to.atTime(LocalTime.MAX) : LocalDateTime.now();
        LocalDateTime resolvedFrom = (from != null) ? from.atStartOfDay()
                : ("day".equals(unit) ? resolvedTo.minusDays(30) : resolvedTo.minusMonths(12));

        if (resolvedFrom.isAfter(resolvedTo)) {
            throw new InvalidRequestException("`from` должно быть раньше `to`");
        }

        DateTimeFormatter labelFormat = "day".equals(unit)
                ? DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
                : DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

        Map<String, long[]> buckets = new HashMap<>();
        Timestamp fromTs = Timestamp.valueOf(resolvedFrom);
        Timestamp toTs = Timestamp.valueOf(resolvedTo);

        String regSql = "SELECT to_char(date_trunc(?, created_at), ?) AS bucket, COUNT(*) "
                + "FROM users WHERE created_at >= ? AND created_at < ? "
                + "GROUP BY bucket ORDER BY bucket";
        jdbc.query(regSql, rs -> {
            String period = rs.getString(1);
            long count = rs.getLong(2);
            long[] cur = buckets.computeIfAbsent(period, k -> new long[3]);
            cur[0] += count;
        }, unit, sqlLabelPattern(unit), fromTs, toTs);

        String loginSql = "SELECT to_char(date_trunc(?, attempt_time), ?) AS bucket, "
                + "COUNT(*) AS total, COUNT(DISTINCT email) AS uniq "
                + "FROM login_attempts WHERE successful = TRUE AND attempt_time >= ? AND attempt_time < ? "
                + "GROUP BY bucket ORDER BY bucket";
        jdbc.query(loginSql, rs -> {
            String period = rs.getString(1);
            long total = rs.getLong(2);
            long uniq = rs.getLong(3);
            long[] cur = buckets.computeIfAbsent(period, k -> new long[3]);
            cur[1] += total;
            cur[2] += uniq;
        }, unit, sqlLabelPattern(unit), fromTs, toTs);

        List<DashboardMetricPointDto> series = new ArrayList<>();
        for (LocalDateTime cursor = truncate(resolvedFrom, unit);
             !cursor.isAfter(resolvedTo);
             cursor = advance(cursor, unit)) {
            String label = cursor.format(labelFormat);
            long[] cur = buckets.getOrDefault(label, new long[3]);
            series.add(DashboardMetricPointDto.builder()
                    .period(label)
                    .registrations(cur[0])
                    .loginsTotal(cur[1])
                    .uniqueLogins(cur[2])
                    .build());
        }

        return DashboardMetricsDto.builder()
                .from(resolvedFrom)
                .to(resolvedTo)
                .granularity(unit)
                .series(series)
                .build();
    }

    private String normalizeGranularity(String requested) {
        if (requested == null || requested.isBlank()) {
            return "month";
        }
        String lower = requested.toLowerCase(Locale.ROOT);
        if (!"day".equals(lower) && !"month".equals(lower)) {
            throw new InvalidRequestException("Поддерживаются granularity month и day");
        }
        return lower;
    }

    private String sqlLabelPattern(String unit) {
        return "day".equals(unit) ? "YYYY-MM-DD" : "YYYY-MM";
    }

    private LocalDateTime truncate(LocalDateTime moment, String unit) {
        LocalDate date = moment.toLocalDate();
        if ("day".equals(unit)) {
            return date.atStartOfDay();
        }
        return date.withDayOfMonth(1).atStartOfDay();
    }

    private LocalDateTime advance(LocalDateTime moment, String unit) {
        return "day".equals(unit) ? moment.plusDays(1) : moment.plusMonths(1);
    }

    private long singleLong(String jpql, Object... params) {
        try {
            var q = em.createQuery(jpql, Long.class);
            for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
            Long v = q.getSingleResult();
            return v == null ? 0L : v;
        } catch (Exception ex) {
            return 0L;
        }
    }

    private BigDecimal singleBigDecimal(String jpql, Object... params) {
        try {
            var q = em.createQuery(jpql, BigDecimal.class);
            for (int i = 0; i < params.length; i++) q.setParameter(i + 1, params[i]);
            BigDecimal v = q.getSingleResult();
            return v == null ? BigDecimal.ZERO : v;
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }
}
