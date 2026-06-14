package kz.hrms.splitupauth.websocket;

import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.SupportTicket;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
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

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class WebSocketAuthChannelInterceptor implements ChannelInterceptor {

    private static final Pattern TICKET_TOPIC = Pattern.compile("^/topic/support-tickets/(\\d+)$");
    private static final Pattern ACCOUNT_TOPIC = Pattern.compile("^/topic/users/(\\d+)/account$");

    private final SupportTicketRepository supportTicketRepository;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }

        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            User user = (User) accessor.getSessionAttributes().get(WebSocketAuthHandshakeInterceptor.SESSION_USER_KEY);
            if (user == null) {
                throw new ForbiddenOperationException("WebSocket authentication required");
            }

            accessor.setUser(new UsernamePasswordAuthenticationToken(
                    user,
                    null,
                    List.of(new SimpleGrantedAuthority(user.getRole().name()))
            ));
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

        User sessionUser = (User) accessor.getSessionAttributes()
                .get(WebSocketAuthHandshakeInterceptor.SESSION_USER_KEY);
        if (sessionUser != null) {
            return sessionUser;
        }

        throw new ForbiddenOperationException("WebSocket authentication required");
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

        Matcher matcher = TICKET_TOPIC.matcher(destination);
        if (matcher.matches()) {
            Long ticketId = Long.parseLong(matcher.group(1));
            SupportTicket ticket = supportTicketRepository.findById(ticketId)
                    .orElseThrow(() -> new ForbiddenOperationException("Ticket not found"));

            if (isStaff(user) || ticket.getUser().getId().equals(user.getId())) {
                return;
            }

            throw new ForbiddenOperationException("Not allowed to subscribe to this ticket");
        }

        throw new ForbiddenOperationException("Unknown subscription destination");
    }

    /**
     * Only the owning user may subscribe to {@code /topic/users/{id}/account}.
     * Staff/admin explicitly do NOT get a backdoor here — the topic is the
     * personal channel used for forced-logout signals, not for moderation.
     */
    private void validateAccountTopic(User user, Long ownerId) {
        if (user.getId() == null || !user.getId().equals(ownerId)) {
            throw new ForbiddenOperationException("Not allowed to subscribe to another user's account topic");
        }
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
