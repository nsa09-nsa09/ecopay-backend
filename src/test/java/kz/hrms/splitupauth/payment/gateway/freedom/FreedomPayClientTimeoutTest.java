package kz.hrms.splitupauth.payment.gateway.freedom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Transport-level guarantees for {@link FreedomPayClient} (Block 2):
 *
 * <ul>
 *   <li>A hung provider is abandoned after the read timeout rather than pinning the caller forever.
 *   <li>The money-moving path ({@link FreedomPayClient#postForm}) issues EXACTLY ONE attempt even on
 *       a timeout — a socket timeout can never spawn a second financial operation.
 *   <li>The read-only path ({@link FreedomPayClient#postFormRetryable}) retries a bounded number of
 *       times.
 * </ul>
 *
 * Uses a JDK {@link HttpServer} that stalls past the client read timeout, counting attempts — fully
 * hermetic, no Spring context or Docker.
 */
class FreedomPayClientTimeoutTest {

  private HttpServer server;
  private final AtomicInteger hits = new AtomicInteger();
  private FreedomPayClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setExecutor(Executors.newFixedThreadPool(4));
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          try {
            // Stall well past the client read timeout so the client aborts and (for money POSTs)
            // must NOT retry.
            Thread.sleep(800);
            byte[] body = "<response><pg_status>ok</pg_status></response>".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
          } catch (Exception ignored) {
            // Client already gone — expected.
          } finally {
            exchange.close();
          }
        });
    server.start();

    FreedomPayProperties props = new FreedomPayProperties();
    props.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    props.setConnectTimeoutMs(500);
    props.setReadTimeoutMs(250);
    props.setConnectionRequestTimeoutMs(500);
    props.setStatusRetryMaxAttempts(2); // → 3 total attempts for the retryable path
    props.setStatusRetryBackoffMs(10);

    client = new FreedomPayClient(props);
    client.initRestClient();
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void moneyMovingPostIsNeverRetriedOnTimeout() {
    assertThrows(
        FreedomPayException.class, () -> client.postForm("/init_payment.php", Map.of("a", "b")));
    assertEquals(1, hits.get(), "money-moving POST must issue exactly one attempt on timeout");
  }

  @Test
  void readOnlyStatusCallRetriesBounded() {
    assertThrows(
        FreedomPayException.class,
        () -> client.postFormRetryable("/get_status.php", Map.of("a", "b")));
    assertEquals(
        3, hits.get(), "read-only status call must retry up to statusRetryMaxAttempts times");
  }
}
