package kz.hrms.splitupauth.websocket;

import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthChannelInterceptorTest {

    @Mock private SupportTicketRepository supportTicketRepository;

    private WebSocketAuthChannelInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthChannelInterceptor(supportTicketRepository);
    }

    private User user(long id, Role role) {
        return User.builder()
                .id(id).email("u" + id + "@e.kz").role(role)
                .status(UserStatus.ACTIVE).build();
    }

    private Message<byte[]> subscribe(User principal, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(destination);
        accessor.setUser(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(principal.getRole().name()))));
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void subscription_toOwnAccountTopic_isAllowed() {
        User u = user(42L, Role.USER);
        assertDoesNotThrow(() -> interceptor.preSend(
                subscribe(u, "/topic/users/42/account"), null));
    }

    @Test
    void subscription_toForeignAccountTopic_isRejected() {
        User u = user(42L, Role.USER);
        assertThrows(ForbiddenOperationException.class,
                () -> interceptor.preSend(subscribe(u, "/topic/users/43/account"), null));
    }

    @Test
    void subscription_adminToForeignAccountTopic_isRejected() {
        // Admins explicitly do NOT get a backdoor on this topic.
        User admin = user(99L, Role.ADMIN);
        assertThrows(ForbiddenOperationException.class,
                () -> interceptor.preSend(subscribe(admin, "/topic/users/42/account"), null));
    }

    @Test
    void subscription_unknownDestination_isRejected() {
        User u = user(42L, Role.USER);
        assertThrows(ForbiddenOperationException.class,
                () -> interceptor.preSend(subscribe(u, "/topic/totally/random"), null));
    }
}
