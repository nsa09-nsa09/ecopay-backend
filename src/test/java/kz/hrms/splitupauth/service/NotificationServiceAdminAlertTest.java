package kz.hrms.splitupauth.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.NotificationRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
class NotificationServiceAdminAlertTest {

  @Mock private NotificationRepository notificationRepository;
  @Mock private NotificationPreferenceService preferenceService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private UserRepository userRepository;

  private NotificationService service;

  @BeforeEach
  void setUp() {
    service =
        new NotificationService(
            notificationRepository,
            preferenceService,
            eventPublisher,
            new ObjectMapper(),
            userRepository);
  }

  @Test
  void firstAlertNotifiesEachActiveAdminExactlyOnce() {
    User a1 = User.builder().id(1L).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
    User a2 = User.builder().id(2L).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
    when(notificationRepository.existsByTypeAndRelatedEntityId(
            NotificationType.PAYMENT_CAPTURE_ANOMALY, 100L))
        .thenReturn(false);
    when(userRepository.findByRoleAndStatus(Role.ADMIN, UserStatus.ACTIVE))
        .thenReturn(List.of(a1, a2));
    when(preferenceService.channelsFor(any(), any()))
        .thenReturn(new NotificationPreferenceService.Channels(true, false));
    when(notificationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

    service.notifyAdminsOnce(
        NotificationType.PAYMENT_CAPTURE_ANOMALY,
        100L,
        Map.of("entityId", "100"),
        "/admin/finance",
        Map.of("intentId", 100L));

    verify(notificationRepository, times(2)).save(any());
  }

  @Test
  void repeatAlertForSameEntityIsSuppressed() {
    when(notificationRepository.existsByTypeAndRelatedEntityId(
            NotificationType.PAYMENT_CAPTURE_ANOMALY, 100L))
        .thenReturn(true);

    service.notifyAdminsOnce(
        NotificationType.PAYMENT_CAPTURE_ANOMALY,
        100L,
        Map.of("entityId", "100"),
        "/admin/finance",
        Map.of());

    verify(userRepository, never()).findByRoleAndStatus(any(), any());
    verify(notificationRepository, never()).save(any());
  }
}
