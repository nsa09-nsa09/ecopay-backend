package kz.hrms.splitupauth.websocket;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.SupportTicket;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

  private static final Pattern TICKET_TOPIC = Pattern.compile("^/topic/support-tickets/(\\d+)$");
  private static final Pattern ACCOUNT_TOPIC = Pattern.compile("^/topic/users/(\\d+)/account$");
  private static final Pattern NOTIFICATIONS_TOPIC =
      Pattern.compile("^/topic/users/(\\d+)/notifications$");
  private static final Pattern ROOM_CHAT_TOPIC = Pattern.compile("^/topic/rooms/(\\d+)/chat$");

  /** Member statuses that count as "has paid" and therefore grant room-chat access. */
  private static final List<MemberStatus> PAID_STATUSES =
      List.of(MemberStatus.PENDING, MemberStatus.ACTIVE);

  private final SupportTicketRepository supportTicketRepository;
  private final RoomRepository roomRepository;
  private final RoomMemberRepository roomMemberRepository;
  private final JwtUtil jwtUtil;
  private final UserRepository userRepository;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null) {
      return message;
    }

    if (StompCommand.CONNECT.equals(accessor.getCommand())) {
      User user = authenticateConnect(accessor);

      accessor.setUser(
          new UsernamePasswordAuthenticationToken(
              user, null, List.of(new SimpleGrantedAuthority(user.getRole().name()))));
      if (accessor.getSessionAttributes() != null) {
        accessor.getSessionAttributes().put(WebSocketAuthHandshakeInterceptor.SESSION_USER_KEY, user);
      }
      return message;
    }

    if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      User user = resolveUser(accessor);
      validateSubscription(user, accessor.getDestination());
    }

    return message;
  }

  private User resolveUser(StompHeaderAccessor accessor) {
    if (accessor.getUser() instanceof UsernamePasswordAuthenticationToken auth
        && auth.getPrincipal() instanceof User user) {
      return user;
    }

    User sessionUser =
        (User)
            accessor.getSessionAttributes().get(WebSocketAuthHandshakeInterceptor.SESSION_USER_KEY);
    if (sessionUser != null) {
      return sessionUser;
    }

    throw new ForbiddenOperationException("WebSocket authentication required");
  }

  private User authenticateConnect(StompHeaderAccessor accessor) {
    String authHeader = accessor.getFirstNativeHeader("Authorization");
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      throw new ForbiddenOperationException("WebSocket authentication required");
    }
    String token = authHeader.substring(7);
    try {
      String subject = jwtUtil.extractUsername(token);
      if (subject == null || !jwtUtil.validateToken(token, subject)) {
        throw new ForbiddenOperationException("WebSocket authentication required");
      }
      User user =
          (subject.contains("@") ? userRepository.findByEmail(subject) : userRepository.findByPublicId(subject))
              .orElseThrow(() -> new ForbiddenOperationException("WebSocket authentication required"));
      if (user.getStatus() != UserStatus.ACTIVE) {
        throw new ForbiddenOperationException("WebSocket authentication required");
      }
      return user;
    } catch (ForbiddenOperationException ex) {
      throw ex;
    } catch (Exception ex) {
      throw new ForbiddenOperationException("WebSocket authentication required");
    }
  }

  private void validateSubscription(User user, String destination) {
    if (destination == null || destination.isBlank()) {
      throw new ForbiddenOperationException("Subscription destination is required");
    }

    if ("/topic/staff/support-queue".equals(destination)) {
      ensureStaff(user);
      return;
    }

    Matcher accountMatcher = ACCOUNT_TOPIC.matcher(destination);
    if (accountMatcher.matches()) {
      validateAccountTopic(user, Long.parseLong(accountMatcher.group(1)));
      return;
    }

    Matcher notificationsMatcher = NOTIFICATIONS_TOPIC.matcher(destination);
    if (notificationsMatcher.matches()) {
      // Same owner-only rule as the account topic: a user's notification
      // stream is personal; staff get no backdoor here.
      validateAccountTopic(user, Long.parseLong(notificationsMatcher.group(1)));
      return;
    }

    Matcher roomChatMatcher = ROOM_CHAT_TOPIC.matcher(destination);
    if (roomChatMatcher.matches()) {
      validateRoomChatTopic(user, Long.parseLong(roomChatMatcher.group(1)));
      return;
    }

    Matcher matcher = TICKET_TOPIC.matcher(destination);
    if (matcher.matches()) {
      Long ticketId = Long.parseLong(matcher.group(1));
      SupportTicket ticket =
          supportTicketRepository
              .findById(ticketId)
              .orElseThrow(() -> new ForbiddenOperationException("Ticket not found"));

      if (isStaff(user) || ticket.getUser().getId().equals(user.getId())) {
        return;
      }

      throw new ForbiddenOperationException("Not allowed to subscribe to this ticket");
    }

    throw new ForbiddenOperationException("Unknown subscription destination");
  }

  /**
   * Only the owning user may subscribe to {@code /topic/users/{id}/account}. Staff/admin explicitly
   * do NOT get a backdoor here — the topic is the personal channel used for forced-logout signals,
   * not for moderation.
   */
  private void validateAccountTopic(User user, Long ownerId) {
    if (user.getId() == null || !user.getId().equals(ownerId)) {
      throw new ForbiddenOperationException(
          "Not allowed to subscribe to another user's account topic");
    }
  }

  /**
   * A room's chat is for its paid participants only: the owner, or a member whose status is
   * PENDING/ACTIVE (i.e. past APPLIED via payment SUCCESS). Staff get no backdoor here — this
   * mirrors {@code RoomChatService.isParticipant}, kept inline so the interceptor depends only on
   * leaf repositories and can't form a startup cycle with the message broker.
   */
  private void validateRoomChatTopic(User user, Long roomId) {
    Room room =
        roomRepository
            .findById(roomId)
            .filter(r -> r.getDeletedAt() == null)
            .orElseThrow(() -> new ForbiddenOperationException("Room not found"));

    if (room.getOwner() != null
        && user.getId() != null
        && user.getId().equals(room.getOwner().getId())) {
      return;
    }

    boolean paidMember =
        roomMemberRepository
            .findByRoomAndUserAndStatusIn(room, user, PAID_STATUSES)
            .filter(m -> m.getDeletedAt() == null)
            .isPresent();
    if (paidMember) {
      return;
    }

    throw new ForbiddenOperationException("Not allowed to subscribe to this room's chat");
  }

  private boolean isStaff(User user) {
    return user.getRole() == Role.ADMIN || user.getRole() == Role.SUPPORT;
  }

  private void ensureStaff(User user) {
    if (!isStaff(user)) {
      throw new ForbiddenOperationException("Support or admin access required");
    }
  }
}
