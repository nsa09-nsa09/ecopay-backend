package kz.hrms.splitupauth.service;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.dto.MemberDashboardDto;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntent;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionStatus;
import kz.hrms.splitupauth.entity.PaymentTransactionType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.ReviewRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the personal dashboard payload for the signed-in user. All money is normalized to KZT via
 * the room's frozen FX snapshot (rooms.price_per_member_kzt, rooms.fx_rate_to_kzt). Reputation,
 * dispute and review counters reuse the existing repositories so the dashboard stays consistent
 * with the public /api/v1/reputation surface.
 */
@Service
@RequiredArgsConstructor
public class MemberDashboardService {

  private final EntityManager em;
  private final RoomMemberRepository roomMemberRepository;
  private final ReviewRepository reviewRepository;
  private final DisputeRepository disputeRepository;
  private final PaymentIntentRepository paymentIntentRepository;
  private final PaymentService paymentService;

  @Transactional(readOnly = true)
  public MemberDashboardDto getMyDashboard(User user) {
    List<RoomMember> memberships =
        roomMemberRepository.findByUserAndDeletedAtIsNullOrderByCreatedAtDesc(user);

    long totalRoomsJoined = memberships.size();
    long joinedRoomsActive =
        memberships.stream().filter(m -> m.getStatus() == MemberStatus.ACTIVE).count();
    long joinedRoomsCompleted =
        memberships.stream()
            .filter(m -> m.getRoom() != null && m.getRoom().getStatus() == RoomStatus.COMPLETED)
            .count();

    BigDecimal monthlySpendKzt =
        memberships.stream()
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
            .map(m -> nullSafe(m.getRoom() == null ? null : m.getRoom().getPricePerMemberKzt()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

    BigDecimal totalSpentKzt =
        singleBigDecimal(
                "SELECT COALESCE(SUM(t.amount * COALESCE(r.fxRateToKzt, 1)), 0) "
                    + "FROM PaymentTransaction t "
                    + "JOIN t.roomMember m "
                    + "JOIN t.room r "
                    + "WHERE m.user = ?1 AND t.type = ?2 AND t.status IN (?3, ?4, ?5)",
                user,
                PaymentTransactionType.CHARGE,
                PaymentTransactionStatus.SUCCESS,
                PaymentTransactionStatus.REFUNDED_PARTIAL,
                PaymentTransactionStatus.REFUNDED_FULL)
            .setScale(2, RoundingMode.HALF_UP);

    BigDecimal totalSavedKzt =
        memberships.stream()
            .filter(m -> m.getStatus() == MemberStatus.ACTIVE && m.getRoom() != null)
            .map(
                m -> {
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
        .build();
  }

  /**
   * Earliest upcoming billing date across all ACTIVE memberships. The date is the member's actual
   * {@code nextBillingAt} (set on the first successful payment); for a legacy member that predates
   * that field it is derived from the first successful payment via {@link BillingSchedule}, without
   * persisting. The amount is the real next charge — tariff share + EcoPay commission ({@link
   * PaymentService#currentChargeBreakdown}) — not the bare per-member price. OTHER-period plans
   * have no renewal and are skipped.
   */
  private NextPayment projectNextPayment(List<RoomMember> memberships) {
    LocalDateTime soonest = null;
    BigDecimal soonestAmount = null;

    for (RoomMember m : memberships) {
      if (m.getStatus() != MemberStatus.ACTIVE || m.getRoom() == null) continue;
      Room r = m.getRoom();
      PeriodType period = r.getPeriodType();
      if (!BillingSchedule.isSupported(period)) continue; // OTHER (and nulls) have no schedule

      LocalDateTime nextBilling = m.getNextBillingAt();
      if (nextBilling == null) {
        LocalDateTime anchor = firstSuccessfulAnchor(m);
        if (anchor == null) continue;
        nextBilling = BillingSchedule.nextAfter(anchor, period);
      }

      if (soonest == null || nextBilling.isBefore(soonest)) {
        soonest = nextBilling;
        soonestAmount = safeChargeAmount(r);
      }
    }
    return new NextPayment(soonest, soonestAmount);
  }

  /** The first successful payment's capture time (anchor), or null when none exists. */
  private LocalDateTime firstSuccessfulAnchor(RoomMember member) {
    Optional<PaymentIntent> first =
        paymentIntentRepository.findFirstByRoomMemberAndStatusOrderByCreatedAtAsc(
            member, PaymentIntentStatus.SUCCESS);
    return first
        .map(p -> p.getCapturedAt() != null ? p.getCapturedAt() : p.getCreatedAt())
        .orElse(null);
  }

  /**
   * share + commission for the next cycle; falls back to the per-member price if pricing is absent.
   */
  private BigDecimal safeChargeAmount(Room room) {
    try {
      return paymentService.currentChargeBreakdown(room).amount();
    } catch (RuntimeException ex) {
      return nullSafe(room.getPricePerMemberKzt());
    }
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
