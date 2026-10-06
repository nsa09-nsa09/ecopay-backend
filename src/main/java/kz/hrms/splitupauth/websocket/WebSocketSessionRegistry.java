package kz.hrms.splitupauth.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Live WebSocket sessions of this instance, so the server can cut a socket it no longer trusts: a
 * banned user's sessions are closed (after the BANNED event had time to arrive), and a session that
 * floods inbound STOMP frames is disconnected. Entries exist only for open sessions, so the maps
 * are bounded by the number of live connections.
 */
@Component
@Slf4j
public class WebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory, DisposableBean {

  /** Close code for policy violations (RFC 6455). */
  static final CloseStatus BANNED = CloseStatus.POLICY_VIOLATION.withReason("Account restricted");

  static final CloseStatus FLOOD = CloseStatus.POLICY_VIOLATION.withReason("Too many messages");

  private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
  private final Map<String, Long> sessionUsers = new ConcurrentHashMap<>();
  private final Map<String, FrameWindow> frameWindows = new ConcurrentHashMap<>();
  private final ScheduledExecutorService closer =
      Executors.newSingleThreadScheduledExecutor(
          r -> {
            Thread t = new Thread(r, "ws-session-closer");
            t.setDaemon(true);
            return t;
          });

  private final int maxFramesPerWindow;
  private final long windowMillis;

  public WebSocketSessionRegistry(
      @Value("${app.websocket.max-inbound-frames:60}") int maxFramesPerWindow,
      @Value("${app.websocket.inbound-window-seconds:10}") int windowSeconds) {
    this.maxFramesPerWindow = Math.max(1, maxFramesPerWindow);
    this.windowMillis = Math.max(1, windowSeconds) * 1000L;
  }

  @Override
  public WebSocketHandler decorate(WebSocketHandler handler) {
    return new WebSocketHandlerDecorator(handler) {
      @Override
      public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.put(session.getId(), session);
        super.afterConnectionEstablished(session);
      }

      @Override
      public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus)
          throws Exception {
        forget(session.getId());
        super.afterConnectionClosed(session, closeStatus);
      }
    };
  }

  /** Associates an authenticated STOMP session with its user (called on CONNECT). */
  public void bindUser(String sessionId, Long userId) {
    if (sessionId != null && userId != null && sessions.containsKey(sessionId)) {
      sessionUsers.put(sessionId, userId);
    }
  }

  /**
   * Counts one inbound frame for the session. Returns false — and schedules the socket to be closed
   * — once the session exceeds its budget for the current window.
   */
  public boolean tryAcceptInboundFrame(String sessionId) {
    if (sessionId == null) {
      return true;
    }
    FrameWindow window = frameWindows.computeIfAbsent(sessionId, id -> new FrameWindow());
    long now = System.currentTimeMillis();
    long start = window.start.get();
    if (now - start >= windowMillis && window.start.compareAndSet(start, now)) {
      window.count.set(0);
    }
    if (window.count.incrementAndGet() <= maxFramesPerWindow) {
      return true;
    }
    log.warn("Closing WebSocket session flooding inbound frames");
    closeLater(sessionId, FLOOD, 0);
    return false;
  }

  /**
   * Closes every session of the user on this instance after {@code delayMillis}, giving an event
   * already published to them (e.g. BANNED) time to be delivered first.
   */
  public int closeUserSessions(Long userId, long delayMillis) {
    int scheduled = 0;
    for (Map.Entry<String, Long> entry : sessionUsers.entrySet()) {
      if (entry.getValue().equals(userId)) {
        closeLater(entry.getKey(), BANNED, delayMillis);
        scheduled++;
      }
    }
    return scheduled;
  }

  int openSessionCount() {
    return sessions.size();
  }

  private void closeLater(String sessionId, CloseStatus status, long delayMillis) {
    closer.schedule(
        () -> close(sessionId, status), Math.max(0, delayMillis), TimeUnit.MILLISECONDS);
  }

  private void close(String sessionId, CloseStatus status) {
    WebSocketSession session = sessions.get(sessionId);
    if (session == null) {
      return;
    }
    try {
      if (session.isOpen()) {
        session.close(status);
      }
    } catch (IOException | RuntimeException e) {
      log.debug("WebSocket close failed: {}", e.getClass().getSimpleName());
    } finally {
      forget(sessionId);
    }
  }

  private void forget(String sessionId) {
    sessions.remove(sessionId);
    sessionUsers.remove(sessionId);
    frameWindows.remove(sessionId);
  }

  @Override
  public void destroy() {
    closer.shutdownNow();
  }

  /** Fixed one-window counter; precise enough to stop a flood without per-frame allocation. */
  private static final class FrameWindow {
    private final AtomicLong start = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger count = new AtomicInteger();
  }
}
