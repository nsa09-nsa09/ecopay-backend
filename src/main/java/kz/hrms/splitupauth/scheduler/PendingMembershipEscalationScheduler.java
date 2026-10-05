package kz.hrms.splitupauth.scheduler;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.service.ModerationService;
import kz.hrms.splitupauth.service.SupportTicketService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class PendingMembershipEscalationScheduler {

  private final RoomMemberRepository roomMemberRepository;
  private final SupportTicketService supportTicketService;
  private final ModerationService moderationService;
  private final SchedulerLock schedulerLock;

  // @Transactional stays on the SCHEDULED method so the surrounding transaction (needed for the
  // loop's lazy access with open-in-view=false) is created by the Spring proxy on the external
  // @Scheduled call. The advisory lock takes its own separate connection inside that transaction.
  @Scheduled(fixedDelay = 300000)
  @Transactional
  public void escalateStalePendingMemberships() {
    schedulerLock.runExclusive(
        SchedulerLock.Key.PENDING_MEMBERSHIP_ESCALATION, this::escalateDueMemberships);
  }

  private void escalateDueMemberships() {
    List<RoomMember> pendingMembers =
        roomMemberRepository.findByStatusAndDeletedAtIsNull(MemberStatus.PENDING);

    LocalDateTime now = LocalDateTime.now();
    LocalDateTime ownerGrantDeadline = now.minusHours(24);
    LocalDateTime memberConfirmDeadline = now.minusHours(24);

    for (RoomMember roomMember : pendingMembers) {
      boolean shouldEscalate = false;
      String subject = "Access issue for room membership";
      String message = null;

      if (roomMember.getOwnerAccessConfirmedAt() == null
          && roomMember.getUpdatedAt() != null
          && roomMember.getUpdatedAt().isBefore(ownerGrantDeadline)) {
        shouldEscalate = true;
        message = "Automatic escalation: owner did not confirm access in time.";
      }

      if (roomMember.getOwnerAccessConfirmedAt() != null
          && roomMember.getMemberConfirmedAt() == null
          && roomMember.getOwnerAccessConfirmedAt().isBefore(memberConfirmDeadline)) {
        shouldEscalate = true;
        message = "Automatic escalation: member did not confirm access in time.";
      }

      if (!shouldEscalate) {
        continue;
      }

      if (!Boolean.TRUE.equals(roomMember.getRequiresAdminReview())) {
        roomMember.setRequiresAdminReview(true);
        roomMemberRepository.save(roomMember);
      }

      supportTicketService.createSystemAccessIssueTicket(roomMember, subject, message);

      moderationService.enqueueMembershipForReview(
          roomMember, "PENDING_TIMEOUT", java.math.BigDecimal.ZERO);
    }
  }
}
