package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class AdminUserInvestigationControllerTest {
  @Mock UserRepository users;
  @Mock RoomRepository rooms;
  @Mock RoomMemberRepository members;
  @Mock UserReportRepository reports;
  @Mock SupportTicketRepository tickets;
  @Mock DisputeRepository disputes;
  @Mock RoomEventLogRepository events;
  @Mock AdminActionLogRepository actions;

  @Test
  void contextContainsOnlyTargetRowsAndQueriesAreBounded() {
    User target =
        User.builder().id(5L).publicId("target").status(UserStatus.ACTIVE).role(Role.USER).build();
    Room room =
        Room.builder()
            .id(7L)
            .owner(target)
            .title("Target room")
            .status(RoomStatus.COMPLETED)
            .build();
    RoomMember member =
        RoomMember.builder().user(target).room(room).status(MemberStatus.ACTIVE).build();
    when(users.findById(5L)).thenReturn(Optional.of(target));
    when(rooms.findByOwnerOrderByCreatedAtDesc(eq(target), any(Pageable.class)))
        .thenReturn(List.of(room));
    when(members.findByUserOrderByCreatedAtDesc(eq(target), any(Pageable.class)))
        .thenReturn(List.of(member));
    when(reports.findByTargetUser_IdOrderByCreatedAtDesc(eq(5L), any(Pageable.class)))
        .thenReturn(List.of());
    when(reports.findByReporter_IdOrderByCreatedAtDesc(eq(5L), any(Pageable.class)))
        .thenReturn(List.of());
    when(events.findByActorUserOrderByCreatedAtDesc(eq(target), any(Pageable.class)))
        .thenReturn(List.of());
    when(actions.findByEntityTypeAndEntityIdOrderByCreatedAtDesc(
            eq("USER"), eq(5L), any(Pageable.class)))
        .thenReturn(List.of());
    var controller =
        new AdminUserInvestigationController(
            users, rooms, members, reports, tickets, disputes, events, actions);
    var context = controller.get(5L);
    assertEquals(7L, context.ownedRooms().get(0).roomId());
    assertEquals("Target room", context.memberships().get(0).roomTitle());
    assertEquals(5L, context.user().getId());
    ArgumentCaptor<Pageable> roomPage = ArgumentCaptor.forClass(Pageable.class);
    verify(rooms).findByOwnerOrderByCreatedAtDesc(eq(target), roomPage.capture());
    assertEquals(50, roomPage.getValue().getPageSize());
    ArgumentCaptor<Pageable> actionPage = ArgumentCaptor.forClass(Pageable.class);
    verify(actions)
        .findByEntityTypeAndEntityIdOrderByCreatedAtDesc(eq("USER"), eq(5L), actionPage.capture());
    assertEquals(20, actionPage.getValue().getPageSize());
    verifyNoMoreInteractions(users);
  }
}
