package kz.hrms.splitupauth.websocket;

import kz.hrms.splitupauth.dto.AccountEventDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * Publishes account-lifecycle events (ban / unban / forced logout) to the
 * affected user's personal STOMP topic. The interceptor in
 * {@link WebSocketAuthChannelInterceptor#validateAccountTopic} guarantees
 * only the owner can subscribe.
 */
@Service
@RequiredArgsConstructor
public class AccountRealtimeService {

    public static final String ACCOUNT_TOPIC_PREFIX = "/topic/users/";
    public static final String ACCOUNT_TOPIC_SUFFIX = "/account";

    private final SimpMessagingTemplate messagingTemplate;

    public static String topicFor(Long userId) {
        return ACCOUNT_TOPIC_PREFIX + userId + ACCOUNT_TOPIC_SUFFIX;
    }

    public void publishBanned(Long userId, String reason, LocalDateTime bannedAt) {
        AccountEventDto event = AccountEventDto.builder()
                .type("BANNED")
                .reason(reason)
                .occurredAt(bannedAt)
                .build();
        messagingTemplate.convertAndSend(topicFor(userId), event);
    }

    public void publishUnbanned(Long userId) {
        AccountEventDto event = AccountEventDto.builder()
                .type("UNBANNED")
                .occurredAt(LocalDateTime.now())
                .build();
        messagingTemplate.convertAndSend(topicFor(userId), event);
    }
}
