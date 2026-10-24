package kz.hrms.splitupauth.scheduler;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.PaymentIntentStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.repository.PaymentIntentRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B11: an APPLIED membership that never gets paid is dead weight — it holds a slot the moment it
 * reaches PENDING, and until then it blocks the member from re-applying because the uq_room_member
 * active index would reject a second row for the same (room, user). Every hour, scan the APPLIED
 * rows that are older than {@code expireAfterHours} and have no payment intent in a live state
 * (PENDING / RECONCILING / SUCCESS), and move them to CANCELLED_BEFORE_PAYMENT so the slot never
 * occupied in the first place is freed cleanly.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AppliedMembershipExpiryScheduler {

  private static final long EXPIRE_AFTER_HOURS = 48;

  private final RoomMemberRepository roomMemberRepository;
  private final PaymentIntentRepository paymentIntentRepository;
  private final SchedulerLock schedulerLock;
  private final PlatformTransactionManager transactionManager;

  @Scheduled(fixedDelay = 3_600_000)
  public void expireStaleApplied() {
    schedulerLock.runExclusive(
        "applied-membership-expiry",
        Duration.ofMinutes(10),
        () -> new TransactionTemplate(transactionManager).executeWithoutResult(s -> run()));
  }

  private void run() {
    LocalDateTime cutoff = LocalDateTime.now().minusHours(EXPIRE_AFTER_HOURS);
    List<RoomMember> stale =
        roomMemberRepository.findByStatusAndDeletedAtIsNull(MemberStatus.APPLIED);
    for (RoomMember member : stale) {
      if (member.getCreatedAt() == null || member.getCreatedAt().isAfter(cutoff)) continue;

      boolean hasLiveIntent =
          paymentIntentRepository
              .findFirstByRoomMember_IdAndStatusInOrderByCreatedAtDesc(
                  member.getId(),
                  List.of(
                      PaymentIntentStatus.PENDING,
                      PaymentIntentStatus.RECONCILING,
                      PaymentIntentStatus.SUCCESS))
              .isPresent();
      if (hasLiveIntent) continue;

      member.setStatus(MemberStatus.CANCELLED_BEFORE_PAYMENT);
      member.setEndedAt(LocalDateTime.now());
      roomMemberRepository.save(member);
      log.info(
          "Auto-expired APPLIED membership id={} (>{}h old, no live intent)",
          member.getId(),
          EXPIRE_AFTER_HOURS);
    }
  }
}
