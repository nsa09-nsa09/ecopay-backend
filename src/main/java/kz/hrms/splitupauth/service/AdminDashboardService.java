package kz.hrms.splitupauth.service;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import kz.hrms.splitupauth.dto.AdminDashboardKpisDto;
import kz.hrms.splitupauth.dto.DashboardMetricPointDto;
import kz.hrms.splitupauth.dto.DashboardMetricsDto;
import kz.hrms.splitupauth.entity.MemberStatus;
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

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

  private final EntityManager em;
  private final JdbcTemplate jdbc;

  @Transactional(readOnly = true)
  public AdminDashboardKpisDto getKpis() {
    long totalUsers = singleLong("SELECT COUNT(u) FROM User u");
    long activeUsers =
        singleLong("SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.ACTIVE);
    long bannedUsers =
        singleLong("SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.BANNED);
    long deletedUsers =
        singleLong("SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.DELETED);
    LocalDateTime thirtyDaysAgo = LocalDateTime.now().minusDays(30);
    long newUsersLast30Days =
        singleLong("SELECT COUNT(u) FROM User u WHERE u.createdAt >= ?1", thirtyDaysAgo);

    long totalRooms = singleLong("SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL");
    long openRooms =
        singleLong(
            "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1",
            RoomStatus.OPEN);
    long activeRooms =
        singleLong(
            "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1",
            RoomStatus.ACTIVE);
    long completedRooms =
        singleLong(
            "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1",
            RoomStatus.COMPLETED);
    long blockedRooms =
        singleLong(
            "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.status = ?1",
            RoomStatus.BLOCKED);

    BigDecimal totalRevenue =
        singleBigDecimal(
            "SELECT COALESCE(SUM(t.amount), 0) FROM PaymentTransaction t "
                + "WHERE t.type = ?1 AND t.status IN (?2, ?3, ?4)",
            PaymentTransactionType.CHARGE,
            PaymentTransactionStatus.SUCCESS,
            PaymentTransactionStatus.REFUNDED_PARTIAL,
            PaymentTransactionStatus.REFUNDED_FULL);
    // Platform income = ECOpay commission on the same successful charges as totalRevenue,
    // so platformRevenue ≤ totalRevenue by construction.
    BigDecimal platformRevenue =
        singleBigDecimal(
            "SELECT COALESCE(SUM(t.paymentIntent.commissionAmount), 0) FROM PaymentTransaction t "
                + "WHERE t.type = ?1 AND t.status IN (?2, ?3, ?4)",
            PaymentTransactionType.CHARGE,
            PaymentTransactionStatus.SUCCESS,
            PaymentTransactionStatus.REFUNDED_PARTIAL,
            PaymentTransactionStatus.REFUNDED_FULL);
    BigDecimal totalRefunds =
        singleBigDecimal(
            "SELECT COALESCE(SUM(r.amount), 0) FROM RefundTransaction r WHERE r.status = ?1",
            RefundStatus.SUCCESS);

    long openDisputes =
        singleLong(
            "SELECT COUNT(d) FROM Dispute d WHERE d.status = kz.hrms.splitupauth.entity.DisputeStatus.OPEN");
    long pendingModeration =
        singleLong(
            "SELECT COUNT(m) FROM ModerationQueue m WHERE m.status IN "
                + "(kz.hrms.splitupauth.entity.ModerationQueueStatus.OPEN, "
                + "kz.hrms.splitupauth.entity.ModerationQueueStatus.IN_REVIEW)");
    long pendingPayouts =
        singleLong(
            "SELECT COUNT(p) FROM Payout p WHERE p.status IN ('PENDING','PENDING_METHOD','PROCESSING')");

    // ----- Visitor counts (site_visit) -----
    LocalDate today = LocalDate.now();
    LocalDate from30 = today.minusDays(30);
    long uniqueVisitorsToday =
        jdbcCountLong("SELECT COUNT(*) FROM site_visit WHERE visit_date = ?", today);
    long uniqueVisitors30d =
        jdbcCountLong(
            "SELECT COUNT(DISTINCT visitor_id) FROM site_visit WHERE visit_date >= ?", from30);
    long totalPageViews30d =
        jdbcCountLong(
            "SELECT COALESCE(SUM(page_count), 0) FROM site_visit WHERE visit_date >= ?", from30);

    // ----- Member rollups -----
    long totalActiveMembers =
        singleLong(
            "SELECT COUNT(m) FROM RoomMember m WHERE m.deletedAt IS NULL AND m.status = ?1",
            MemberStatus.ACTIVE);
    double avgMembersPerRoom =
        totalRooms == 0
            ? 0.0
            : Math.round(((double) totalActiveMembers / totalRooms) * 100.0) / 100.0;

    // V27 added price_per_member_kzt; for rooms created before V27 the column is backfilled
    // (currency=KZT → fx=1), so this expression is safe for all rows. Per-room price ×
    // active member count, summed across all rooms.
    BigDecimal totalActiveSubscriptionsValueKzt =
        jdbcBigDecimal(
            "SELECT COALESCE(SUM(r.price_per_member_kzt * m.cnt), 0) "
                + "FROM rooms r JOIN ( "
                + "  SELECT room_id, COUNT(*) AS cnt FROM room_members "
                + "  WHERE deleted_at IS NULL AND status = 'ACTIVE' GROUP BY room_id "
                + ") m ON m.room_id = r.id "
                + "WHERE r.deleted_at IS NULL AND r.status = 'ACTIVE'");

    long newRoomsLast30Days =
        singleLong(
            "SELECT COUNT(r) FROM Room r WHERE r.deletedAt IS NULL AND r.createdAt >= ?1",
            thirtyDaysAgo);

    double conversionVisitorToUser30d =
        uniqueVisitors30d == 0
            ? 0.0
            : Math.round(((double) newUsersLast30Days / uniqueVisitors30d) * 10000.0) / 100.0;
    double refundRatePercent =
        totalRevenue == null || totalRevenue.signum() == 0
            ? 0.0
            : totalRefunds
                .divide(totalRevenue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(2, RoundingMode.HALF_UP)
                .doubleValue();

    long openTickets =
        singleLong(
            "SELECT COUNT(t) FROM SupportTicket t WHERE t.status IN ("
                + "kz.hrms.splitupauth.entity.SupportTicketStatus.OPEN, "
                + "kz.hrms.splitupauth.entity.SupportTicketStatus.IN_PROGRESS, "
                + "kz.hrms.splitupauth.entity.SupportTicketStatus.WAITING_USER, "
                + "kz.hrms.splitupauth.entity.SupportTicketStatus.ESCALATED)");

    // Average per-room fill ratio: occupied seats (PENDING+ACTIVE) over max_members, then avg.
    Double avgFill =
        jdbc.query(
            "SELECT AVG( occ::numeric / NULLIF(r.max_members, 0) ) FROM rooms r "
                + "LEFT JOIN ( "
                + "  SELECT room_id, COUNT(*) AS occ FROM room_members "
                + "  WHERE deleted_at IS NULL AND status IN ('PENDING','ACTIVE') GROUP BY room_id "
                + ") m ON m.room_id = r.id "
                + "WHERE r.deleted_at IS NULL",
            rs -> rs.next() ? rs.getDouble(1) : 0.0);
    double avgRoomFillRate = avgFill == null ? 0.0 : Math.round(avgFill * 10000.0) / 10000.0;

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
        .platformRevenue(platformRevenue)
        .totalRefunds(totalRefunds)
        .openDisputes(openDisputes)
        .pendingModeration(pendingModeration)
        .pendingPayouts(pendingPayouts)
        .uniqueVisitorsToday(uniqueVisitorsToday)
        .uniqueVisitors30d(uniqueVisitors30d)
        .totalPageViews30d(totalPageViews30d)
        .totalActiveMembersAcrossRooms(totalActiveMembers)
        .avgMembersPerRoom(avgMembersPerRoom)
        .totalActiveSubscriptionsValueKzt(totalActiveSubscriptionsValueKzt)
        .newRoomsLast30Days(newRoomsLast30Days)
        .conversionVisitorToUser30d(conversionVisitorToUser30d)
        .refundRatePercent(refundRatePercent)
        .openTickets(openTickets)
        .avgRoomFillRate(avgRoomFillRate)
        .build();
  }

  /**
   * Time-series for the admin dashboard chart. registrations counts rows in users (by created_at);
   * logins counts successful login_attempts in the same bucket. SQL is grouped via date_trunc to
   * keep this O(distinct buckets) instead of pulling all rows into Java.
   */
  @Transactional(readOnly = true)
  public DashboardMetricsDto getMetrics(String granularity, LocalDate from, LocalDate to) {
    String unit = normalizeGranularity(granularity);
    // The controller binds calendar dates (yyyy-MM-dd). Widen `from` to the
    // start of the day and `to` to the end of the day so a single-day filter
    // ("from=to") still includes rows recorded later that same day.
    LocalDateTime resolvedTo = (to != null) ? to.atTime(LocalTime.MAX) : LocalDateTime.now();
    LocalDateTime resolvedFrom =
        (from != null)
            ? from.atStartOfDay()
            : ("day".equals(unit) ? resolvedTo.minusDays(30) : resolvedTo.minusMonths(12));

    if (resolvedFrom.isAfter(resolvedTo)) {
      throw new InvalidRequestException("`from` должно быть раньше `to`");
    }

    DateTimeFormatter labelFormat =
        "day".equals(unit)
            ? DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
            : DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);

    // bucket → [registrations, loginsTotal, uniqueLogins, visitors, pageViews, newRooms]
    Map<String, long[]> buckets = new HashMap<>();
    Map<String, BigDecimal> revenueBuckets = new HashMap<>();
    Map<String, BigDecimal> commissionBuckets = new HashMap<>();
    Timestamp fromTs = Timestamp.valueOf(resolvedFrom);
    Timestamp toTs = Timestamp.valueOf(resolvedTo);

    String regSql =
        "SELECT to_char(date_trunc(?, created_at), ?) AS bucket, COUNT(*) "
            + "FROM users WHERE created_at >= ? AND created_at < ? "
            + "GROUP BY bucket ORDER BY bucket";
    jdbc.query(
        regSql,
        rs -> {
          String period = rs.getString(1);
          long count = rs.getLong(2);
          long[] cur = buckets.computeIfAbsent(period, k -> new long[6]);
          cur[0] += count;
        },
        unit,
        sqlLabelPattern(unit),
        fromTs,
        toTs);

    String loginSql =
        "SELECT to_char(date_trunc(?, attempt_time), ?) AS bucket, "
            + "COUNT(*) AS total, COUNT(DISTINCT email) AS uniq "
            + "FROM login_attempts WHERE successful = TRUE AND attempt_time >= ? AND attempt_time < ? "
            + "GROUP BY bucket ORDER BY bucket";
    jdbc.query(
        loginSql,
        rs -> {
          String period = rs.getString(1);
          long total = rs.getLong(2);
          long uniq = rs.getLong(3);
          long[] cur = buckets.computeIfAbsent(period, k -> new long[6]);
          cur[1] += total;
          cur[2] += uniq;
        },
        unit,
        sqlLabelPattern(unit),
        fromTs,
        toTs);

    // Visits: each row is already (visitor, day) unique, so COUNT(*) per bucket is unique visitors.
    // page_count summed gives the total page views in the bucket.
    String visitSql =
        "SELECT to_char(date_trunc(?, visit_date), ?) AS bucket, "
            + "COUNT(*) AS visitors, COALESCE(SUM(page_count), 0) AS views "
            + "FROM site_visit WHERE visit_date >= ? AND visit_date <= ? "
            + "GROUP BY bucket ORDER BY bucket";
    jdbc.query(
        visitSql,
        rs -> {
          String period = rs.getString(1);
          long visitors = rs.getLong(2);
          long views = rs.getLong(3);
          long[] cur = buckets.computeIfAbsent(period, k -> new long[6]);
          cur[3] += visitors;
          cur[4] += views;
        },
        unit,
        sqlLabelPattern(unit),
        java.sql.Date.valueOf(resolvedFrom.toLocalDate()),
        java.sql.Date.valueOf(resolvedTo.toLocalDate()));

    String roomSql =
        "SELECT to_char(date_trunc(?, created_at), ?) AS bucket, COUNT(*) "
            + "FROM rooms WHERE deleted_at IS NULL AND created_at >= ? AND created_at < ? "
            + "GROUP BY bucket ORDER BY bucket";
    jdbc.query(
        roomSql,
        rs -> {
          String period = rs.getString(1);
          long count = rs.getLong(2);
          long[] cur = buckets.computeIfAbsent(period, k -> new long[6]);
          cur[5] += count;
        },
        unit,
        sqlLabelPattern(unit),
        fromTs,
        toTs);

    // Revenue: successful CHARGE transactions in the bucket, in KZT.
    // Transactions are denominated in the room currency; we convert through the
    // room snapshot fx rate so all buckets are comparable in KZT.
    String revenueSql =
        "SELECT to_char(date_trunc(?, t.created_at), ?) AS bucket, "
            + "COALESCE(SUM(t.amount * COALESCE(r.fx_rate_to_kzt, 1)), 0) AS revenue "
            + "FROM payment_transactions t "
            + "LEFT JOIN rooms r ON r.id = t.room_id "
            + "WHERE t.type = 'CHARGE' AND t.status IN ('SUCCESS','REFUNDED_PARTIAL','REFUNDED_FULL') "
            + "  AND t.created_at >= ? AND t.created_at < ? "
            + "GROUP BY bucket ORDER BY bucket";
    jdbc.query(
        revenueSql,
        rs -> {
          String period = rs.getString(1);
          BigDecimal revenue = rs.getBigDecimal(2);
          revenueBuckets.merge(
              period, revenue == null ? BigDecimal.ZERO : revenue, BigDecimal::add);
        },
        unit,
        sqlLabelPattern(unit),
        fromTs,
        toTs);

    // Platform commission (real ECOpay income) in the same window and buckets as revenue,
    // using the same fx conversion so the two series are directly comparable in KZT.
    String commissionSql =
        "SELECT to_char(date_trunc(?, t.created_at), ?) AS bucket, "
            + "COALESCE(SUM(pi.commission_amount * COALESCE(r.fx_rate_to_kzt, 1)), 0) AS commission "
            + "FROM payment_transactions t "
            + "JOIN payment_intents pi ON pi.id = t.payment_intent_id "
            + "LEFT JOIN rooms r ON r.id = t.room_id "
            + "WHERE t.type = 'CHARGE' AND t.status IN ('SUCCESS','REFUNDED_PARTIAL','REFUNDED_FULL') "
            + "  AND t.created_at >= ? AND t.created_at < ? "
            + "GROUP BY bucket ORDER BY bucket";
    jdbc.query(
        commissionSql,
        rs -> {
          String period = rs.getString(1);
          BigDecimal commission = rs.getBigDecimal(2);
          commissionBuckets.merge(
              period, commission == null ? BigDecimal.ZERO : commission, BigDecimal::add);
        },
        unit,
        sqlLabelPattern(unit),
        fromTs,
        toTs);

    List<DashboardMetricPointDto> series = new ArrayList<>();
    for (LocalDateTime cursor = truncate(resolvedFrom, unit);
        !cursor.isAfter(resolvedTo);
        cursor = advance(cursor, unit)) {
      String label = cursor.format(labelFormat);
      long[] cur = buckets.getOrDefault(label, new long[6]);
      BigDecimal revenue = revenueBuckets.getOrDefault(label, BigDecimal.ZERO);
      BigDecimal commissionRevenue = commissionBuckets.getOrDefault(label, BigDecimal.ZERO);
      series.add(
          DashboardMetricPointDto.builder()
              .period(label)
              .registrations(cur[0])
              .loginsTotal(cur[1])
              .uniqueLogins(cur[2])
              .uniqueVisitors(cur[3])
              .pageViews(cur[4])
              .newRooms(cur[5])
              .revenue(revenue)
              .commissionRevenue(commissionRevenue)
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

  private long jdbcCountLong(String sql, Object... params) {
    try {
      Long v = jdbc.queryForObject(sql, Long.class, params);
      return v == null ? 0L : v;
    } catch (Exception ex) {
      return 0L;
    }
  }

  private BigDecimal jdbcBigDecimal(String sql, Object... params) {
    try {
      BigDecimal v = jdbc.queryForObject(sql, BigDecimal.class, params);
      return v == null ? BigDecimal.ZERO : v;
    } catch (Exception ex) {
      return BigDecimal.ZERO;
    }
  }
}
