package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.DisputeStatus;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SupportTicketStatus;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies the 72-hour deemed-confirmation rule without waiving any later refund rights. */
@Service
@RequiredArgsConstructor
public class AccessConfirmationService {

  private static final List<SupportTicketStatus> OPEN_TICKET_STATUSES =
      List.of(
          SupportTicketStatus.OPEN,
          SupportTicketStatus.IN_PROGRESS,
          SupportTicketStatus.WAITING_USER,
          SupportTicketStatus.ESCALATED);

  private final RoomMemberRepository roomMemberRepository;
  private final SupportTicketRepository supportTicketRepository;
  private final DisputeRepository disputeRepository;
  private final RoomMemberService roomMemberService;
  private final RoomEventLogger roomEventLogger;

  @Transactional
  public int processDue(LocalDateTime now, int limit) {
    int processed = 0;
    for (Long id :
        roomMemberRepository.findDueDeemedConfirmationIds(now, PageRequest.of(0, limit))) {
      if (applyOne(id, now)) processed++;
    }
    return processed;
  }

  protected boolean applyOne(Long id, LocalDateTime now) {
    RoomMember member = roomMemberRepository.findWithLockById(id).orElse(null);
    if (member == null
        || member.getStatus() != MemberStatus.PENDING
        || member.getOwnerAccessConfirmedAt() == null
        || member.getMemberConfirmedAt() != null
        || member.getAccessDeemedConfirmedAt() != null
        || member.getAccessConfirmationDeadlineAt() == null
        || member.getAccessConfirmationDeadlineAt().isAfter(now)
        || Boolean.TRUE.equals(member.getRequiresAdminReview())) {
      return false;
    }
    if (supportTicketRepository.existsByRoomMemberAndStatusIn(member, OPEN_TICKET_STATUSES)
        || disputeRepository.existsByRoomMemberAndStatusIn(
            member, List.of(DisputeStatus.OPEN, DisputeStatus.UNDER_REVIEW))) {
      return false;
    }

    member.setAccessDeemedConfirmedAt(now);
    roomMemberRepository.save(member);
    roomEventLogger.log(
        member.getRoom(),
        member,
        null,
        "SYSTEM",
        "access_deemed_confirmed",
        java.util.Map.of("deadline", member.getAccessConfirmationDeadlineAt().toString()));
    roomMemberService.activateAfterDeemedConfirmation(member);
    return true;
  }
}
