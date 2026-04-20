package kz.hrms.splitupauth.scheduler;

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

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class PendingMembershipEscalationScheduler {

    private final RoomMemberRepository roomMemberRepository;
    private final SupportTicketService supportTicketService;
    private final ModerationService moderationService;
    @Scheduled(fixedDelay = 300000)
    @Transactional
    public void escalateStalePendingMemberships() {
        List<RoomMember> pendingMembers = roomMemberRepository.findByStatusAndDeletedAtIsNull(MemberStatus.PENDING);

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
                    roomMember,
                    "PENDING_TIMEOUT",
                    java.math.BigDecimal.ZERO
            );
        }
    }
}