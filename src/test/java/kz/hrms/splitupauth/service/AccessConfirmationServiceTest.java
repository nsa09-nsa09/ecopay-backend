package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.repository.DisputeRepository;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccessConfirmationServiceTest {

  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private SupportTicketRepository supportTicketRepository;
  @Mock private DisputeRepository disputeRepository;
  @Mock private RoomMemberService roomMemberService;
  @Mock private RoomEventLogger roomEventLogger;

  private AccessConfirmationService service;

  @BeforeEach
  void setUp() {
    service =
        new AccessConfirmationService(
            roomMemberRepository,
            supportTicketRepository,
            disputeRepository,
            roomMemberService,
            roomEventLogger);
  }

  @Test
  void dueMembershipWithoutComplaintIsDeemedConfirmed() {
    LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
    RoomMember member = dueMember(now);
    when(roomMemberRepository.findDueDeemedConfirmationIds(any(), any())).thenReturn(List.of(1L));
    when(roomMemberRepository.findWithLockById(1L)).thenReturn(Optional.of(member));

    assertEquals(1, service.processDue(now, 100));
    assertNotNull(member.getAccessDeemedConfirmedAt());
    verify(roomMemberService).activateAfterDeemedConfirmation(member);
  }

  @Test
  void openTicketPreventsDeemedConfirmation() {
    LocalDateTime now = LocalDateTime.of(2026, 9, 2, 12, 0);
    RoomMember member = dueMember(now);
    when(roomMemberRepository.findDueDeemedConfirmationIds(any(), any())).thenReturn(List.of(1L));
    when(roomMemberRepository.findWithLockById(1L)).thenReturn(Optional.of(member));
    when(supportTicketRepository.existsByRoomMemberAndStatusIn(any(), any())).thenReturn(true);

    assertEquals(0, service.processDue(now, 100));
    verify(roomMemberService, never()).activateAfterDeemedConfirmation(any());
  }

  private static RoomMember dueMember(LocalDateTime now) {
    return RoomMember.builder()
        .id(1L)
        .room(Room.builder().id(2L).build())
        .status(MemberStatus.PENDING)
        .requiresAdminReview(false)
        .ownerAccessConfirmedAt(now.minusHours(73))
        .accessConfirmationDeadlineAt(now.minusHours(1))
        .build();
  }
}
