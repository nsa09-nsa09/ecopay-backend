package kz.hrms.splitupauth.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

class WebSocketSessionRegistryTest {

  private final WebSocketSessionRegistry registry = new WebSocketSessionRegistry(60, 10);
  private final WebSocketHandler handler = registry.decorate(mock(WebSocketHandler.class));

  @AfterEach
  void tearDown() {
    registry.destroy();
  }

  private WebSocketSession open(String id) throws Exception {
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn(id);
    when(session.isOpen()).thenReturn(true);
    handler.afterConnectionEstablished(session);
    return session;
  }

  @Test
  void banClosesOnlyTheBannedUsersSessions() throws Exception {
    WebSocketSession bannedTab1 = open("a");
    WebSocketSession bannedTab2 = open("b");
    WebSocketSession bystander = open("c");
    registry.bindUser("a", 7L);
    registry.bindUser("b", 7L);
    registry.bindUser("c", 8L);

    assertEquals(2, registry.closeUserSessions(7L, 0));

    verify(bannedTab1, timeout(2_000)).close(WebSocketSessionRegistry.BANNED);
    verify(bannedTab2, timeout(2_000)).close(WebSocketSessionRegistry.BANNED);
    verify(bystander, after(200).never()).close(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void closedSessionsAreForgotten_soTheRegistryStaysBounded() throws Exception {
    WebSocketSession session = open("x");
    registry.bindUser("x", 9L);
    registry.tryAcceptInboundFrame("x");

    handler.afterConnectionClosed(session, CloseStatus.NORMAL);

    assertEquals(0, registry.openSessionCount());
    assertEquals(0, registry.closeUserSessions(9L, 0));
    verify(session, never()).close(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void floodingSessionIsClosedWithPolicyViolation() throws Exception {
    WebSocketSessionRegistry strict = new WebSocketSessionRegistry(2, 60);
    WebSocketHandler strictHandler = strict.decorate(mock(WebSocketHandler.class));
    WebSocketSession session = mock(WebSocketSession.class);
    when(session.getId()).thenReturn("f");
    when(session.isOpen()).thenReturn(true);
    strictHandler.afterConnectionEstablished(session);
    try {
      strict.tryAcceptInboundFrame("f");
      strict.tryAcceptInboundFrame("f");
      assertEquals(false, strict.tryAcceptInboundFrame("f"));
      verify(session, timeout(2_000)).close(WebSocketSessionRegistry.FLOOD);
    } finally {
      strict.destroy();
    }
  }
}
