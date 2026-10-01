package kz.hrms.splitupauth.service;

import java.time.LocalDateTime;
import java.util.List;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.websocket.AccountRealtimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AccountRestrictionTransitions {
  private final UserRepository userRepository;
  private final AdminActionLogRepository auditRepository;
  private final TokenRevocationService tokenRevocationService;
  private final AccountRealtimeService realtimeService;
  private final NotificationService notificationService;

  @Transactional
  public void activate(Long userId) {
    User user = userRepository.findByIdForUpdate(userId).orElse(null);
    if (user == null || user.getStatus() != UserStatus.ACTIVE || user.getBanStartsAt() == null)
      return;
    LocalDateTime now = LocalDateTime.now();
    if (user.getBanStartsAt().isAfter(now)) return;
    if (user.getBanUntil() != null && !user.getBanUntil().isAfter(now)) {
      expireUser(user);
      return;
    }
    user.setStatus(UserStatus.BANNED);
    user.setBannedAt(now);
    userRepository.save(user);
    tokenRevocationService.revokeAllUserTokens(user);
    realtimeService.publishBanned(userId, user.getBanReason(), now);
    notificationService.notify(
        user,
        NotificationType.ACCOUNT_BANNED,
        "Аккаунт заблокирован",
        "Ваш аккаунт был заблокирован. Причина: " + user.getBanReason(),
        null,
        null);
    audit(user, AdminActionType.USER_RESTRICTION_ACTIVATED);
  }

  @Transactional
  public void expire(Long userId) {
    User user = userRepository.findByIdForUpdate(userId).orElse(null);
    if (user == null || user.getBanUntil() == null) return;
    LocalDateTime now = LocalDateTime.now();
    if (user.getBanUntil().isAfter(now)) return;
    expireUser(user);
  }

  private void expireUser(User user) {
    boolean wasBanned = user.getStatus() == UserStatus.BANNED;
    user.setStatus(UserStatus.ACTIVE);
    user.setBanReason(null);
    user.setBannedAt(null);
    user.setBanStartsAt(null);
    user.setBanUntil(null);
    userRepository.save(user);
    if (wasBanned) {
      realtimeService.publishUnbanned(user.getId());
      notificationService.notify(
          user,
          NotificationType.ACCOUNT_UNBANNED,
          "Аккаунт разблокирован",
          "Срок блокировки истёк.",
          null,
          null);
    }
    audit(user, AdminActionType.USER_RESTRICTION_EXPIRED);
  }

  private void audit(User user, AdminActionType type) {
    var source =
        auditRepository.findFirstByEntityTypeAndEntityIdAndActionTypeInOrderByCreatedAtDesc(
            "USER",
            user.getId(),
            List.of(AdminActionType.USER_RESTRICTION_SCHEDULED, AdminActionType.USER_BANNED));
    source.ifPresent(
        row ->
            auditRepository.save(
                AdminActionLog.builder()
                    .adminUser(row.getAdminUser())
                    .actionType(type)
                    .entityType("USER")
                    .entityId(user.getId())
                    .reason(row.getReason())
                    .build()));
  }
}
