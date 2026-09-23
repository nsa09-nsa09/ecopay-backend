package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.dto.AccountRestrictionRequest;
import kz.hrms.splitupauth.dto.AdminDecisionRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.*;
import kz.hrms.splitupauth.service.AvatarStorageService;
import kz.hrms.splitupauth.service.NotificationService;
import kz.hrms.splitupauth.service.TokenRevocationService;
import kz.hrms.splitupauth.websocket.AccountRealtimeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

@ExtendWith(MockitoExtension.class)
class AdminUserRestrictionTest {
  @Mock UserRepository users;
  @Mock AdminActionLogRepository audit;
  @Mock RoomRepository rooms;
  @Mock RoomMemberRepository members;
  @Mock SupportTicketRepository tickets;
  @Mock DisputeRepository disputes;
  @Mock TokenRevocationService tokens;
  @Mock AccountRealtimeService realtime;
  @Mock NotificationService notifications;
  @Mock AvatarStorageService avatars;
  AdminUserController controller;
  User target;
  User admin;

  @BeforeEach
  void setUp() {
    controller =
        new AdminUserController(
            users,
            audit,
            rooms,
            members,
            tickets,
            disputes,
            null,
            new ObjectMapper(),
            tokens,
            realtime,
            notifications,
            avatars,
            null);
    target = User.builder().id(2L).status(UserStatus.ACTIVE).role(Role.USER).build();
    admin = User.builder().id(1L).status(UserStatus.ACTIVE).role(Role.ADMIN).build();
  }

  @Test
  void scheduledBanStaysActiveUntilStartAndUnbanCancelsIt() {
    when(users.findById(2L)).thenReturn(Optional.of(target));
    LocalDateTime start = LocalDateTime.now().plusDays(1);
    LocalDateTime end = start.plusDays(7);
    controller.restrict(
        2L,
        admin,
        new AccountRestrictionRequest("Investigation", start, end),
        new MockHttpServletRequest());
    assertEquals(UserStatus.ACTIVE, target.getStatus());
    assertEquals(start, target.getBanStartsAt());
    assertEquals(end, target.getBanUntil());
    assertNull(target.getBannedAt());
    verifyNoInteractions(tokens, realtime);
    ArgumentCaptor<AdminActionLog> log = ArgumentCaptor.forClass(AdminActionLog.class);
    verify(audit).save(log.capture());
    assertEquals(AdminActionType.USER_RESTRICTION_SCHEDULED, log.getValue().getActionType());

    AdminDecisionRequest cancel = new AdminDecisionRequest();
    cancel.setReason("Cancel schedule");
    controller.unban(2L, admin, cancel, new MockHttpServletRequest());
    assertEquals(UserStatus.ACTIVE, target.getStatus());
    assertNull(target.getBanStartsAt());
    assertNull(target.getBanUntil());
    assertNull(target.getBanReason());
    verifyNoInteractions(tokens, realtime);
  }

  @Test
  void immediateTemporaryBanRevokesTokensAndPreservesIdentifiers() {
    when(users.findById(2L)).thenReturn(Optional.of(target));
    target.setEmail("reserved@test.kz");
    target.setPhone("+77051234565");
    target.setSlug("reserved-slug");
    controller.restrict(
        2L,
        admin,
        new AccountRestrictionRequest("Fraud", null, LocalDateTime.now().plusDays(2)),
        new MockHttpServletRequest());
    assertEquals(UserStatus.BANNED, target.getStatus());
    assertNotNull(target.getBannedAt());
    assertNotNull(target.getBanStartsAt());
    assertEquals("reserved@test.kz", target.getEmail());
    assertEquals("+77051234565", target.getPhone());
    assertEquals("reserved-slug", target.getSlug());
    verify(tokens).revokeAllUserTokens(target);
    verify(realtime).publishBanned(eq(2L), eq("Fraud"), any());
  }
}
