package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.dto.NotificationDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.entity.Notification;
import kz.hrms.splitupauth.entity.NotificationType;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single entry point for emitting user notifications and for the bell/read APIs.
 *
 * <p>Delivery model (satisfies the "notifications must not block user-facing API" / outbox rule in
 * the project spec):
 *
 * <ol>
 *   <li>{@link #notify} runs inside the caller's domain transaction. It resolves the user's channel
 *       preferences, and — if in-app is on — persists the {@link Notification} row atomically with
 *       the state change that triggered it.
 *   <li>It then publishes a {@link NotificationDeliveryEvent}. The actual STOMP push and email
 *       happen in {@code NotificationDeliveryListener} AFTER the transaction commits, on an async
 *       thread. A rolled-back change therefore neither persists a row nor pushes/emails.
 * </ol>
 *
 * {@link #notify} is intentionally best-effort: any failure building/saving a notification is
 * logged and swallowed so it can never break the business operation that triggered it.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

  private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
  private static final int MAX_PAGE_SIZE = 100;

  private final NotificationRepository notificationRepository;
  private final NotificationPreferenceService preferenceService;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectMapper objectMapper;

  // ===================== emit =====================

  /**
   * Emit a notification to {@code recipient}. Honors the recipient's channel preferences. Never
   * throws — a notification failure must not abort the triggering business operation.
   *
   * @param link optional frontend-relative deep link (e.g. {@code /rooms/member/42})
   * @param metadata optional structured context; serialized to the jsonb column
   */
  @Transactional
  public void notify(
      User recipient,
      NotificationType type,
      String title,
      String body,
      String link,
      Map<String, Object> metadata) {
    if (recipient == null || recipient.getId() == null) {
      return;
    }
    try {
      NotificationPreferenceService.Channels channels =
          preferenceService.channelsFor(recipient, type);
      if (!channels.inApp() && !channels.email()) {
        return;
      }

      NotificationDto dto = null;
      if (channels.inApp()) {
        JsonNode metaNode =
            metadata == null || metadata.isEmpty() ? null : objectMapper.valueToTree(metadata);

        Notification notification =
            Notification.builder()
                .user(recipient)
                .type(type)
                .title(title)
                .body(body)
                .link(link)
                .metadata(metaNode)
                .build();
        notification = notificationRepository.save(notification);
        dto = NotificationDto.from(notification);
      }

      String currentFrontend = EmailService.currentFrontendOrigin();
      eventPublisher.publishEvent(
          new NotificationDeliveryEvent(
              recipient.getId(),
              channels.email() ? recipient.getEmail() : null,
              dto,
              channels.email(),
              title,
              body,
              link,
              MailLocale.from(recipient.getLocale()),
              currentFrontend));
    } catch (Exception e) {
      // Best-effort: log and move on so the triggering operation succeeds.
      log.warn(
          "Failed to emit notification type={} to user={}: {}",
          type,
          recipient.getId(),
          e.getMessage());
    }
  }

  /** Convenience overload for notifications with no deep link / metadata. */
  @Transactional
  public void notify(User recipient, NotificationType type, String title, String body) {
    notify(recipient, type, title, body, null, null);
  }

  // ===================== read surface =====================

  @Transactional(readOnly = true)
  public PagedResponse<NotificationDto> list(User user, int page, int size) {
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize);
    Page<Notification> result =
        notificationRepository.findByUserOrderByCreatedAtDesc(user, pageable);

    List<NotificationDto> items = result.getContent().stream().map(NotificationDto::from).toList();

    return PagedResponse.<NotificationDto>builder()
        .items(items)
        .page(result.getNumber())
        .size(result.getSize())
        .totalItems(result.getTotalElements())
        .totalPages(result.getTotalPages())
        .hasNext(result.hasNext())
        .hasPrevious(result.hasPrevious())
        .build();
  }

  @Transactional(readOnly = true)
  public long unreadCount(User user) {
    return notificationRepository.countByUserAndReadAtIsNull(user);
  }

  /** Mark a single notification read. IDOR-guarded: must belong to the caller. */
  @Transactional
  public void markRead(User user, Long notificationId) {
    Notification notification =
        notificationRepository
            .findById(notificationId)
            .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));
    if (!notification.getUser().getId().equals(user.getId())) {
      // Don't leak existence — same response as not-found.
      throw new ResourceNotFoundException("Notification not found");
    }
    if (notification.getReadAt() == null) {
      notification.setReadAt(LocalDateTime.now());
      notificationRepository.save(notification);
    }
  }

  @Transactional
  public int markAllRead(User user) {
    return notificationRepository.markAllRead(user, LocalDateTime.now());
  }
}
