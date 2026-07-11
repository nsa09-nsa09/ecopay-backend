package kz.hrms.splitupauth.websocket;

import kz.hrms.splitupauth.dto.RoomChatMessageDto;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

/**
 * Broadcasts a persisted {@link RoomChatMessageDto} to a room's chat topic. Subscription is gated
 * to the room's paid participants by {@link
 * WebSocketAuthChannelInterceptor#validateSubscription} via the {@code /topic/rooms/{id}/chat}
 * pattern.
 */
@Service
@RequiredArgsConstructor
public class RoomChatRealtimeService {

  public static final String ROOM_CHAT_TOPIC_PREFIX = "/topic/rooms/";
  public static final String ROOM_CHAT_TOPIC_SUFFIX = "/chat";

  private final SimpMessagingTemplate messagingTemplate;

  public static String topicFor(Long roomId) {
    return ROOM_CHAT_TOPIC_PREFIX + roomId + ROOM_CHAT_TOPIC_SUFFIX;
  }

  public void broadcast(Long roomId, RoomChatMessageDto message) {
    messagingTemplate.convertAndSend(topicFor(roomId), message);
  }
}
