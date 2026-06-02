package kz.hrms.splitupauth.service;

import jakarta.persistence.EntityManager;
import kz.hrms.splitupauth.dto.AdminDashboardKpisDto;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.RefundStatus;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
@RequiredArgsConstructor
public class AdminDashboardService {

    private final EntityManager em;

    @Transactional(readOnly = true)
    public AdminDashboardKpisDto getKpis() {
        long totalUsers = singleLong("SELECT COUNT(u) FROM User u");
        long activeUsers = singleLong(
                "SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.ACTIVE);
        long bannedUsers = singleLong(
                "SELECT COUNT(u) FROM User u WHERE u.status = ?1", UserStatus.BANNED);

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
