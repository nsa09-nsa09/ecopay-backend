package kz.hrms.splitupauth.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.service.NotificationService;
import kz.hrms.splitupauth.service.PaymentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Daily renewal lifecycle nudges for the manual-renewal model (no auto-charge). For each ACTIVE
 * member whose plan has a billing period:
 *
 * <ul>
 *   <li>once the renewal window opens and the period is unpaid, send one {@code RENEWAL_DUE} per
 *       period to the member;
 *   <li>once the period lapses past its grace with no payment, stamp {@code renewalOverdueSince}
 *       and notify the member and the owner with {@code RENEWAL_OVERDUE}.
 * </ul>
 *
 * It never excludes or bans anyone — an overdue membership is left for an admin to handle through
 * the existing dispute flow. Runs on one replica at a time, in bounded id-ordered batches.
 */
@Component
@Slf4j
public class RenewalReminderScheduler {

  private static final int BATCH_SIZE = 200;

  private static final List<PaymentIntentStatus> CAPTURED_STATUSES =
      List.of(
          PaymentIntentStatus.SUCCESS,
          PaymentIntentStatus.REFUND_REQUIRED,
          PaymentIntentStatus.REFUND_PENDING,
          PaymentIntentStatus.REFUNDED,
          PaymentIntentStatus.REQUIRES_REVIEW,
          PaymentIntentStatus.CAPTURE_ANOMALY);

  private final RoomMemberRepository roomMemberRepository;
  private final PaymentIntentRepository paymentIntentRepository;
  private final NotificationService notificationService;
  private final PaymentService paymentService;
  private final SchedulerLock schedulerLock;
  private final Clock clock;
  private final TransactionTemplate tx;

  @Value("${app.renewal.window-days:5}")
  private long windowDays;

  @Value("${app.renewal.grace-days:3}")
  private long graceDays;

  public RenewalReminderScheduler(
      RoomMemberRepository roomMemberRepository,
      PaymentIntentRepository paymentIntentRepository,
      NotificationService notificationService,
      PaymentService paymentService,
      SchedulerLock schedulerLock,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.roomMemberRepository = roomMemberRepository;
    this.paymentIntentRepository = paymentIntentRepository;
    this.notificationService = notificationService;
    this.paymentService = paymentService;
    this.schedulerLock = schedulerLock;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /** Runs every day at 04:30 server time, on one replica at a time. */
  @Scheduled(cron = "0 30 4 * * *")
  public void runDailyReminders() {
    schedulerLock.runExclusive("renewal-reminders", Duration.ofHours(2), this::runAllBatches);
  }

  /** Scans ACTIVE members in id order, {@value #BATCH_SIZE} at a time. */
  public int runAllBatches() {
    long lastId = 0;
    int scanned = 0;
    while (true) {
      long cursor = lastId;
      List<Long> ids =
          tx.execute(
              status ->
                  roomMemberRepository.findActiveIdsAfter(
                      MemberStatus.ACTIVE, cursor, PageRequest.of(0, BATCH_SIZE)));
      if (ids == null || ids.isEmpty()) {
        break;
      }
      for (Long memberId : ids) {
        try {
          tx.executeWithoutResult(status -> processMember(memberId));
        } catch (RuntimeException ex) {
          log.warn(
              "Renewal reminder failed for member {}: {}", memberId, ex.getClass().getSimpleName());
        }
      }
      scanned += ids.size();
      lastId = ids.get(ids.size() - 1);
    }
    log.info("RenewalReminderScheduler: done, scanned {} active members", scanned);
    return scanned;
  }

  private void processMember(Long memberId) {
    RoomMember member = roomMemberRepository.findWithLockById(memberId).orElse(null);
    if (member == null
        || member.getStatus() != MemberStatus.ACTIVE
        || member.getDeletedAt() != null
        || member.getUser() == null
        || member.getRoom() == null) {
      return;
    }
    Room room = member.getRoom();
    LocalDateTime period = member.getNextBillingAt();
    if (period == null
        || (room.getPeriodType() != PeriodType.MONTHLY
            && room.getPeriodType() != PeriodType.YEARLY)) {
      return;
    }
    if (hasCapturedForPeriod(memberId, period)) {
      return; // already renewed for this period
    }

    LocalDateTime now = LocalDateTime.now(clock);
    boolean windowOpen =
        !now.isBefore(period.minusDays(windowDays)) && !now.isAfter(period.plusDays(graceDays));
    boolean pastGrace = now.isAfter(period.plusDays(graceDays));

    if (windowOpen && !period.equals(member.getRenewalRemindedFor())) {
      notifyDue(member, room, period);
      member.setRenewalRemindedFor(period);
      roomMemberRepository.save(member);
      return;
    }

    if (pastGrace && member.getRenewalOverdueSince() == null) {
      member.setRenewalOverdueSince(now);
      roomMemberRepository.save(member);
      notifyOverdue(member, room);
    }
  }

  private void notifyDue(RoomMember member, Room room, LocalDateTime period) {
    PaymentService.ChargeBreakdown breakdown = paymentService.currentChargeBreakdown(room);
    notificationService.notify(
        member.getUser(),
        NotificationType.RENEWAL_DUE,
        Map.of(
            "roomTitle",
            room.getTitle() == null ? "" : room.getTitle(),
            "amount",
            String.valueOf(breakdown.amount()),
            "currency",
            room.getCurrency() == null ? "KZT" : room.getCurrency(),
            "dueDate",
            period.toLocalDate().toString()),
        "/rooms/member/" + room.getId(),
        Map.of("roomId", room.getId(), "roomMemberId", member.getId()));
  }

  private void notifyOverdue(RoomMember member, Room room) {
    Map<String, String> params =
        Map.of("roomTitle", room.getTitle() == null ? "" : room.getTitle());
    notificationService.notify(
        member.getUser(),
        NotificationType.RENEWAL_OVERDUE,
        params,
        "/rooms/member/" + room.getId(),
        Map.of("roomId", room.getId(), "roomMemberId", member.getId()));
    if (room.getOwner() != null) {
      notificationService.notify(
          room.getOwner(),
          NotificationType.RENEWAL_OVERDUE,
          params,
          "/rooms/" + room.getId(),
          Map.of("roomId", room.getId(), "roomMemberId", member.getId()));
    }
  }

  private boolean hasCapturedForPeriod(Long memberId, LocalDateTime period) {
    return !paymentIntentRepository
        .findByRoomMember_IdAndBillingPeriodStartAndStatusInOrderByCreatedAtDesc(
            memberId, period, CAPTURED_STATUSES)
        .isEmpty();
  }
}
