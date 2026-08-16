package kz.hrms.splitupauth.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.Room;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.SupportTicket;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.ForbiddenOperationException;
import kz.hrms.splitupauth.repository.RoomMemberRepository;
import kz.hrms.splitupauth.repository.RoomRepository;
import kz.hrms.splitupauth.repository.SupportTicketRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.util.JwtUtil;
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

@ExtendWith(MockitoExtension.class)
class WebSocketAuthChannelInterceptorTest {

  @Mock private SupportTicketRepository supportTicketRepository;
  @Mock private RoomRepository roomRepository;
  @Mock private RoomMemberRepository roomMemberRepository;
  @Mock private JwtUtil jwtUtil;
  @Mock private UserRepository userRepository;

  private WebSocketAuthChannelInterceptor interceptor;

  @BeforeEach
  void setUp() {
    interceptor =
        new WebSocketAuthChannelInterceptor(
            supportTicketRepository, roomRepository, roomMemberRepository, jwtUtil, userRepository);
  }

  private User user(long id, Role role) {
    return User.builder()
        .id(id)
        .email("u" + id + "@e.kz")
        .publicId("pub-" + id)
        .role(role)
        .status(UserStatus.ACTIVE)
        .build();
  }

  private Message<byte[]> connect(String authorization) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
    if (authorization != null) {
      accessor.addNativeHeader("Authorization", authorization);
    }
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private Message<byte[]> subscribe(User principal, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setUser(
        new UsernamePasswordAuthenticationToken(
            principal, null, List.of(new SimpleGrantedAuthority(principal.getRole().name()))));
    accessor.setLeaveMutable(true);
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  @Test
  void connect_withValidBearer_setsAuthenticatedPrincipal() {
    User u = user(42L, Role.USER);
    when(jwtUtil.extractUsername("jwt")).thenReturn("pub-42");
    when(jwtUtil.validateToken("jwt", "pub-42")).thenReturn(true);
    when(userRepository.findByPublicId("pub-42")).thenReturn(Optional.of(u));

    Message<?> result = interceptor.preSend(connect("Bearer jwt"), null);

    StompHeaderAccessor accessor =
        org.springframework.messaging.support.MessageHeaderAccessor.getAccessor(
            result, StompHeaderAccessor.class);
    org.junit.jupiter.api.Assertions.assertNotNull(accessor.getUser());
  }

  @Test
  void connect_withoutAuthorization_isRejected() {
    assertThrows(
        ForbiddenOperationException.class, () -> interceptor.preSend(connect(null), null));
  }

  @Test
  void connect_withInvalidJwt_isRejected() {
    when(jwtUtil.extractUsername("bad")).thenReturn("pub-42");
    when(jwtUtil.validateToken("bad", "pub-42")).thenReturn(false);

    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(connect("Bearer bad"), null));
  }

  @Test
  void subscription_toOwnAccountTopic_isAllowed() {
    User u = user(42L, Role.USER);
    assertDoesNotThrow(() -> interceptor.preSend(subscribe(u, "/topic/users/42/account"), null));
  }

  @Test
  void subscription_toForeignAccountTopic_isRejected() {
    User u = user(42L, Role.USER);
    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(subscribe(u, "/topic/users/43/account"), null));
  }

  @Test
  void subscription_adminToForeignAccountTopic_isRejected() {
    // Admins explicitly do NOT get a backdoor on this topic.
    User admin = user(99L, Role.ADMIN);
    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(subscribe(admin, "/topic/users/42/account"), null));
  }

  @Test
  void subscription_toForeignNotificationsTopic_isRejected() {
    User u = user(42L, Role.USER);
    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(subscribe(u, "/topic/users/43/notifications"), null));
  }

  @Test
  void subscription_unknownDestination_isRejected() {
    User u = user(42L, Role.USER);
    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(subscribe(u, "/topic/totally/random"), null));
  }

  private Room room(long id, User owner) {
    return Room.builder().id(id).owner(owner).build();
  }

  @Test
  void subscription_roomChat_byOwner_isAllowed() {
    User owner = user(7L, Role.USER);
    Room room = room(100L, owner);
    lenient().when(roomRepository.findById(100L)).thenReturn(Optional.of(room));

    assertDoesNotThrow(() -> interceptor.preSend(subscribe(owner, "/topic/rooms/100/chat"), null));
  }

  @Test
  void subscription_roomChat_byPaidMember_isAllowed() {
    User owner = user(7L, Role.USER);
    User member = user(8L, Role.USER);
    Room room = room(100L, owner);
    lenient().when(roomRepository.findById(100L)).thenReturn(Optional.of(room));
    lenient()
        .when(roomMemberRepository.findByRoomAndUserAndStatusIn(eq(room), eq(member), any()))
        .thenReturn(Optional.of(RoomMember.builder().status(MemberStatus.ACTIVE).build()));

    assertDoesNotThrow(() -> interceptor.preSend(subscribe(member, "/topic/rooms/100/chat"), null));
  }

  @Test
  void subscription_roomChat_byNonParticipant_isRejected() {
    User outsider = user(9L, Role.USER);
    Room room = room(100L, user(7L, Role.USER));
    lenient().when(roomRepository.findById(100L)).thenReturn(Optional.of(room));
    lenient()
        .when(roomMemberRepository.findByRoomAndUserAndStatusIn(any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(subscribe(outsider, "/topic/rooms/100/chat"), null));
  }

  @Test
  void subscription_roomChat_appliedMember_isRejected() {
    // APPLIED (not yet paid) never matches the PENDING/ACTIVE query, so the
    // repository returns empty and the subscribe is refused.
    User applied = user(8L, Role.USER);
    Room room = room(100L, user(7L, Role.USER));
    lenient().when(roomRepository.findById(100L)).thenReturn(Optional.of(room));
    lenient()
        .when(roomMemberRepository.findByRoomAndUserAndStatusIn(any(), any(), any()))
        .thenReturn(Optional.empty());

    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(subscribe(applied, "/topic/rooms/100/chat"), null));
  }

  @Test
  void subscription_supportTicket_byOwner_isAllowed() {
    User owner = user(55L, Role.USER);
    when(supportTicketRepository.findById(10L))
        .thenReturn(Optional.of(SupportTicket.builder().id(10L).user(owner).build()));

    assertDoesNotThrow(
        () -> interceptor.preSend(subscribe(owner, "/topic/support-tickets/10"), null));
  }

  @Test
  void subscription_supportTicket_byUnrelatedUser_isRejected() {
    User owner = user(55L, Role.USER);
    User other = user(56L, Role.USER);
    when(supportTicketRepository.findById(10L))
        .thenReturn(Optional.of(SupportTicket.builder().id(10L).user(owner).build()));

    assertThrows(
        ForbiddenOperationException.class,
        () -> interceptor.preSend(subscribe(other, "/topic/support-tickets/10"), null));
  }
}
