package kz.hrms.splitupauth.service;

import jakarta.persistence.EntityManager;
import kz.hrms.splitupauth.dto.MemberDashboardDto;
import kz.hrms.splitupauth.dto.RoomEventLogDto;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomEventLogRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Builds the personal dashboard payload for the signed-in user. All money is
 * normalized to KZT via the room's frozen FX snapshot (rooms.price_per_member_kzt,
 * rooms.fx_rate_to_kzt). Reputation, dispute and review counters reuse the
 * existing repositories so the dashboard stays consistent with the public
 * /api/v1/reputation surface.
 */
@Service
@RequiredArgsConstructor
public class MemberDashboardService {

    private final EntityManager em;
    private final RoomMemberRepository roomMemberRepository;
    private final ReviewRepository reviewRepository;
    private final DisputeRepository disputeRepository;
    private final RoomEventLogRepository roomEventLogRepository;

    private static final int RECENT_EVENT_LIMIT = 5;

    @Transactional(readOnly = true)
    public MemberDashboardDto getMyDashboard(User user) {
        List<RoomMember> memberships = roomMemberRepository
                .findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user);

        long totalRoomsJoined = memberships.size();
        long joinedRoomsActive = memberships.stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .count();
        long joinedRoomsCompleted = memberships.stream()
                .filter(m -> m.getRoom() != null && m.getRoom().getStatus() == RoomStatus.COMPLETED)
                .count();

        BigDecimal monthlySpendKzt = memberships.stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .map(m -> nullSafe(m.getRoom() == null ? null : m.getRoom().getPricePerMemberKzt()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalSpentKzt = singleBigDecimal(
                "SELECT COALESCE(SUM(t.amount * COALESCE(r.fxRateToKzt, 1)), 0) "
                        + "FROM PaymentTransaction t "
                        + "JOIN t.roomMember m "
                        + "JOIN t.room r "
                        + "WHERE m.user = ?1 AND t.type = ?2 AND t.status IN (?3, ?4, ?5)",
                user,
                PaymentTransactionType.CHARGE,
                PaymentTransactionStatus.SUCCESS,
                PaymentTransactionStatus.REFUNDED_PARTIAL,
                PaymentTransactionStatus.REFUNDED_FULL
        ).setScale(2, RoundingMode.HALF_UP);

        BigDecimal totalSavedKzt = memberships.stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getRoom() != null)
                .map(m -> {
                    Room r = m.getRoom();
                    BigDecimal total = nullSafe(r.getPriceTotalKzt());
                    BigDecimal share = nullSafe(r.getPricePerMemberKzt());
                    BigDecimal savings = total.subtract(share);
                    return savings.signum() > 0 ? savings : BigDecimal.ZERO;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        NextPayment next = projectNextPayment(memberships);

        long reviewsReceived = reviewRepository.countByRecipientAndHiddenByAdminFalse(user);
        long disputesAsMember = disputeRepository.countByOpenedByUser(user);

        List<RoomEventLogDto> recentEvents = roomEventLogRepository
                .findByActorUserOrderByCreatedAtDesc(user, PageRequest.of(0, RECENT_EVENT_LIMIT))
                .stream()
                .map(log -> RoomEventLogDto.builder()
                        .id(log.getId())
                        .eventId(log.getEventId() != null ? log.getEventId().toString() : null)
                        .actorUserId(log.getActorUser() != null ? log.getActorUser().getId() : null)
                        .actorRole(log.getActorRole())
                        .roomId(log.getRoom() != null ? log.getRoom().getId() : null)
                        .roomMemberId(log.getRoomMember() != null ? log.getRoomMember().getId() : null)
                        .eventType(log.getEventType())
                        .oldState(log.getOldState())
                        .newState(log.getNewState())
                        // ipAddress + userAgent are intentionally omitted on the self surface
                        // (user's own request, no admin context, less to leak in screenshots).
                        .createdAt(log.getCreatedAt())
                        .build())
                .toList();

        return MemberDashboardDto.builder()
                .joinedRoomsActive(joinedRoomsActive)
                .joinedRoomsCompleted(joinedRoomsCompleted)
                .totalRoomsJoined(totalRoomsJoined)
                .monthlySpendKzt(monthlySpendKzt)
                .totalSpentKzt(totalSpentKzt)
                .totalSavedKzt(totalSavedKzt)
                .nextPaymentDate(next.date)
                .nextPaymentAmountKzt(next.amount)
                .reputationScore(user.getReputation())
                .reviewsReceived(reviewsReceived)
                .disputesAsMember(disputesAsMember)
                .recentEvents(recentEvents)
                .build();
    }

    /**
     * Earliest upcoming billing date across all ACTIVE memberships. Approximated as
     * room.startDate + 1 cycle (monthly = 30 days, yearly = 365). Members whose
     * start date is already in the past also produce a projection by rolling
     * the cycle forward until it sits in the future.
     */
    private NextPayment projectNextPayment(List<RoomMember> memberships) {
        LocalDateTime soonest = null;
        BigDecimal soonestAmount = null;
        LocalDateTime now = LocalDateTime.now();

        for (RoomMember m : memberships) {
            if (m.getStatus() != MemberStatus.ACTIVE || m.getRoom() == null) continue;
            Room r = m.getRoom();
            LocalDateTime base = r.getStartDate();
            if (base == null) continue;
            LocalDateTime candidate = nextCycleAfter(base, r.getPeriodType(), now);
            if (candidate == null) continue;
            if (soonest == null || candidate.isBefore(soonest)) {
                soonest = candidate;
                soonestAmount = nullSafe(r.getPricePerMemberKzt());
            }
        }
        return new NextPayment(soonest, soonestAmount);
    }

    private LocalDateTime nextCycleAfter(LocalDateTime base, PeriodType period, LocalDateTime now) {
        LocalDateTime cursor = base;
        int safety = 0;
        while (cursor.isBefore(now) && safety++ < 1000) {
            cursor = advance(cursor, period);
        }
        return cursor;
    }

    private LocalDateTime advance(LocalDateTime moment, PeriodType period) {
        if (period == null) {
            return moment.plusMonths(1);
        }
        return switch (period) {
            case MONTHLY -> moment.plusMonths(1);
            case YEARLY  -> moment.plusYears(1);
            case OTHER   -> moment.plusMonths(1);
        };
    }

    private BigDecimal nullSafe(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
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

    private record NextPayment(LocalDateTime date, BigDecimal amount) {}
}
