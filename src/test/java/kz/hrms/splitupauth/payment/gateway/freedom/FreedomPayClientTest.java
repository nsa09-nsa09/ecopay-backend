package kz.hrms.splitupauth.payment.gateway.freedom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import kz.hrms.splitupauth.payment.gateway.GatewayRequestNotSentException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/** Exercises the real HTTP client against a local fake FreedomPay. No network, no real money. */
class FreedomPayClientTest {

  private static final String SECRET = "merchant-secret";

  private HttpServer server;
  private final AtomicReference<String> nextBody = new AtomicReference<>();
  private final AtomicReference<String> lastRequest = new AtomicReference<>();
  private final AtomicInteger hits = new AtomicInteger();
  private final AtomicInteger delayMs = new AtomicInteger();
  private final AtomicInteger status = new AtomicInteger(200);
  private FreedomPayProperties properties;
  private FreedomPaySignatureService signatures;

  @BeforeEach
  void startServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/",
        exchange -> {
          hits.incrementAndGet();
          lastRequest.set(
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
          try {
            Thread.sleep(delayMs.get());
          } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
          }
          byte[] body = nextBody.get().getBytes(StandardCharsets.UTF_8);
          exchange.sendResponseHeaders(status.get(), body.length);
          try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
          } catch (IOException ignored) {
            // client gave up (timeout test)
          }
        });
    server.start();
    properties = new FreedomPayProperties();
    properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    properties.setSecretKey(SECRET);
    properties.setReadTimeoutMs(1_000);
    properties.setConnectTimeoutMs(1_000);
    signatures = new FreedomPaySignatureService(properties);
  }

  @AfterEach
  void stopServer() {
    server.stop(0);
  }

  private FreedomPayClient client() {
    return new FreedomPayClient(
        properties,
        signatures,
        new DefaultListableBeanFactory()
            .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
  }

  private String signedXml(String script, Map<String, String> fields) {
    Map<String, String> all = new LinkedHashMap<>(fields);
    all.put("pg_sig", signatures.sign(script, fields, SECRET));
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><response>");
    all.forEach(
        (k, v) ->
            xml.append('<').append(k).append('>').append(v).append("</").append(k).append('>'));
    return xml.append("</response>").toString();
  }

  @Test
  void signsRequestAndAcceptsCorrectlySignedResponse() {
    nextBody.set(
        signedXml(
            "get_status3.php",
            Map.of("pg_status", "ok", "pg_payment_status", "success", "pg_salt", "x")));

    FreedomPayResponse response =
        client()
            .call(
                "status",
                "/get_status3.php",
                "get_status3.php",
                Map.of("pg_payment_id", "1"),
                SECRET,
                true);

    assertTrue(response.isOk());
    assertEquals("success", response.get("pg_payment_status"));
    assertTrue(lastRequest.get().contains("pg_sig="), "request must be signed");
  }

  @Test
  void invalidResponseSignatureFailsClosed() {
    String xml =
        signedXml(
                "init_payment.php", Map.of("pg_status", "ok", "pg_payment_id", "7", "pg_salt", "x"))
            .replace("<pg_payment_id>7<", "<pg_payment_id>8<"); // tampered after signing
    nextBody.set(xml);

    assertThrows(
        FreedomPaySignatureException.class,
        () ->
            client()
                .call(
                    "init_payment",
                    "/init_payment.php",
                    "init_payment.php",
                    Map.of(),
                    SECRET,
                    false));
  }

  @Test
  void responseSignedForAnotherScriptIsRejected() {
    nextBody.set(signedXml("revoke", Map.of("pg_status", "ok", "pg_salt", "x")));

    assertThrows(
        FreedomPaySignatureException.class,
        () -> client().call("payout", "/api/reg2reg", "reg2reg", Map.of(), SECRET, false));
  }

  @Test
  void unsignedSuccessIsRejectedButUnsignedErrorIsReturnedAsUnsignedError() {
    nextBody.set("<response><pg_status>ok</pg_status><pg_payment_id>1</pg_payment_id></response>");
    assertThrows(
        FreedomPaySignatureException.class,
        () ->
            client()
                .call(
                    "init_payment",
                    "/init_payment.php",
                    "init_payment.php",
                    Map.of(),
                    SECRET,
                    false));

    nextBody.set(
        "<response><pg_status>error</pg_status><pg_error_code>9998</pg_error_code></response>");
    FreedomPayResponse error =
        client()
            .call("init_payment", "/init_payment.php", "init_payment.php", Map.of(), SECRET, false);
    assertFalse(error.signed());
    assertFalse(error.isOk(), "an unsigned answer can never be success");
    assertEquals("9998", error.get("pg_error_code"));
  }

  @Test
  void oversizedResponseIsRejectedBeforeParsing() {
    properties.setMaxResponseBytes(1024);
    nextBody.set(
        "<response><pg_status>ok</pg_status><junk>" + "a".repeat(4096) + "</junk></response>");

    assertThrows(
        FreedomPayException.class,
        () ->
            client()
                .call("status", "/get_status3.php", "get_status3.php", Map.of(), SECRET, false));
  }

  @Test
  void xxeDoctypeIsRefused() {
    nextBody.set(
        "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
            + "<response><pg_status>&x;</pg_status></response>");

    assertThrows(
        FreedomPayException.class,
        () ->
            client()
                .call("status", "/get_status3.php", "get_status3.php", Map.of(), SECRET, false));
  }

  @Test
  void moneyMovingTimeoutIsAmbiguousAndNeverRetried() {
    delayMs.set(2_500);
    nextBody.set(signedXml("reg2reg", Map.of("pg_status", "ok", "pg_salt", "x")));

    FreedomPayException ex =
        assertThrows(
            FreedomPayException.class,
            () -> client().call("payout", "/api/reg2reg", "reg2reg", Map.of(), SECRET, false));
    assertFalse(ex instanceof FreedomPaySignatureException);
    assertEquals(1, hits.get(), "a timed-out money-moving call must not be re-sent");
  }

  @Test
  void readOnlyCallsRetryBoundedOnTransientErrors() {
    status.set(502);
    nextBody.set("bad gateway");
    properties.setReadRetries(2);

    assertThrows(
        FreedomPayException.class,
        () ->
            client().call("status", "/get_status3.php", "get_status3.php", Map.of(), SECRET, true));
    assertEquals(3, hits.get(), "1 attempt + 2 bounded retries");
  }

  @Test
  void refusedConnectionIsReportedAsNotSent() {
    server.stop(0);

    assertThrows(
        GatewayRequestNotSentException.class,
        () -> client().call("payout", "/api/reg2reg", "reg2reg", Map.of(), SECRET, false));
  }
}
