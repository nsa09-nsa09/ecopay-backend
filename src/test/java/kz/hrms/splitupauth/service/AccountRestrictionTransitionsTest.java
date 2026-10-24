package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Optional;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.websocket.AccountRealtimeService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountRestrictionTransitionsTest {
  @Mock UserRepository users;
  @Mock AdminActionLogRepository audit;
  @Mock TokenRevocationService tokens;
  @Mock AccountRealtimeService realtime;
  @Mock NotificationService notifications;

  @Test
  void activatesAndExpiresOnceWithAuditAndTokenRevocation() {
    User admin = User.builder().id(7L).build();
    User user =
        User.builder()
            .id(8L)
            .status(UserStatus.ACTIVE)
            .banReason("Investigation")
            .banStartsAt(LocalDateTime.now().minusMinutes(1))
            .banUntil(LocalDateTime.now().plusMinutes(2))
            .build();
    when(users.findByIdForUpdate(8L)).thenReturn(Optional.of(user));
    when(audit.findFirstByEntityTypeAndEntityIdAndActionTypeInOrderByCreatedAtDesc(
            eq("USER"), eq(8L), any()))
        .thenReturn(
            Optional.of(AdminActionLog.builder().adminUser(admin).reason("Investigation").build()));
    var transitions =
        new AccountRestrictionTransitions(users, audit, tokens, realtime, notifications);
    transitions.activate(8L);
    assertEquals(UserStatus.BANNED, user.getStatus());
    assertNotNull(user.getBannedAt());
    verify(tokens).revokeAllUserTokens(user);
    verify(realtime).publishBanned(eq(8L), eq("Investigation"), any());
    transitions.activate(8L);
    verify(tokens, times(1)).revokeAllUserTokens(user);

    user.setBanUntil(LocalDateTime.now().minusNanos(1));
    transitions.expire(8L);
    assertEquals(UserStatus.ACTIVE, user.getStatus());
    assertNull(user.getBanStartsAt());
    assertNull(user.getBanUntil());
    assertNull(user.getBanReason());
    transitions.expire(8L);
    verify(realtime, times(1)).publishUnbanned(8L);
    ArgumentCaptor<AdminActionLog> logs = ArgumentCaptor.forClass(AdminActionLog.class);
    verify(audit, times(2)).save(logs.capture());
    assertEquals(
        AdminActionType.USER_RESTRICTION_ACTIVATED, logs.getAllValues().get(0).getActionType());
    assertEquals(
        AdminActionType.USER_RESTRICTION_EXPIRED, logs.getAllValues().get(1).getActionType());
  }
}
