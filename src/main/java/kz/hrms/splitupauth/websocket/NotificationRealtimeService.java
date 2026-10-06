package kz.hrms.splitupauth.websocket;

import kz.hrms.splitupauth.dto.NotificationDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Pushes a freshly created {@link NotificationDto} to the recipient's personal notifications topic.
 * Subscription is owner-only, enforced by {@link
 * WebSocketAuthChannelInterceptor#validateSubscription} via the {@code
 * /topic/users/{id}/notifications} pattern.
 */
@Service
@RequiredArgsConstructor
public class NotificationRealtimeService {

  public static final String NOTIFICATIONS_TOPIC_PREFIX = "/topic/users/";
  public static final String NOTIFICATIONS_TOPIC_SUFFIX = "/notifications";

  private final SimpMessagingTemplate messagingTemplate;

  public static String topicFor(Long userId) {
    return NOTIFICATIONS_TOPIC_PREFIX + userId + NOTIFICATIONS_TOPIC_SUFFIX;
  }

  public void push(Long userId, NotificationDto notification) {
    messagingTemplate.convertAndSend(topicFor(userId), notification);
  }
}
