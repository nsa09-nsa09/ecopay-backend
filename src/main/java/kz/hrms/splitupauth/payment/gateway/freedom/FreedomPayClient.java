package kz.hrms.splitupauth.payment.gateway.freedom;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import kz.hrms.splitupauth.payment.gateway.GatewayRequestNotSentException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Hardened HTTP client for the FreedomPay Merchant API.
 *
 * <ul>
 *   <li>Explicit connect/read timeouts; the JDK client pools keep-alive connections.
 *   <li>Bulkhead: at most {@code maxConcurrentRequests} in-flight calls per instance, so a slow
 *       provider cannot absorb every request thread. A caller that cannot get a slot within {@code
 *       acquireTimeoutMs} fails with {@link GatewayRequestNotSentException} — nothing was sent.
 *   <li>Response bodies are capped at {@code maxResponseBytes} before XML parsing.
 *   <li>Every request is signed here and every response's {@code pg_sig} is verified with the same
 *       script name and secret (fail closed, {@link FreedomPaySignatureException}). The only
 *       unsigned answer accepted is an explicit {@code pg_status=error} (FreedomPay cannot sign
 *       when it fails to identify the merchant), and it can never be read as success.
 *   <li>Only read-only calls (status/list) are retried, with bounded jittered backoff. A
 *       money-moving call that times out is surfaced to the caller as ambiguous and must be
 *       reconciled by status — it is never re-sent from here.
 *   <li>Logs carry the operation name only; parameters, card data and secrets are never logged.
 *   <li>Metrics: {@code ecopay.freedompay.request} timer tagged by operation and outcome.
 * </ul>
 */
@Component
@Slf4j
public class FreedomPayClient {

  private final FreedomPayProperties properties;
  private final FreedomPaySignatureService signatureService;
  private final ObjectProvider<MeterRegistry> meterRegistry;
  private final HttpClient httpClient;
  private final Semaphore bulkhead;

  public FreedomPayClient(
      FreedomPayProperties properties,
      FreedomPaySignatureService signatureService,
      ObjectProvider<MeterRegistry> meterRegistry) {
    this.properties = properties;
    this.signatureService = signatureService;
    this.meterRegistry = meterRegistry;
    this.httpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(Math.max(500, properties.getConnectTimeoutMs())))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    this.bulkhead = new Semaphore(Math.max(1, properties.getMaxConcurrentRequests()), true);
  }

  /**
   * Signs {@code params} with {@code script}/{@code secret}, posts them form-encoded to {@code
   * path} and returns the verified response.
   *
   * @param operation bounded-cardinality metric/log label, e.g. {@code init_payment}
   * @param readOnly true only for status/list queries; enables bounded retries
   */
  public FreedomPayResponse call(
      String operation,
      String path,
      String script,
      Map<String, String> params,
      String secret,
      boolean readOnly) {
    Map<String, String> signed = new LinkedHashMap<>(params);
    signed.remove(FreedomPaySignatureService.SIGNATURE_FIELD);
    signed.put(
        FreedomPaySignatureService.SIGNATURE_FIELD, signatureService.sign(script, signed, secret));

    int attempts = readOnly ? 1 + Math.max(0, properties.getReadRetries()) : 1;
    RuntimeException last = null;
    for (int attempt = 1; attempt <= attempts; attempt++) {
      try {
        return callOnce(operation, path, script, signed, secret);
      } catch (FreedomPaySignatureException | GatewayRequestNotSentException ex) {
        throw ex;
      } catch (FreedomPayException ex) {
        last = ex;
        if (attempt < attempts) {
          sleepBackoff(attempt);
        }
      }
    }
    throw last;
  }

  private FreedomPayResponse callOnce(
      String operation, String path, String script, Map<String, String> form, String secret) {
    long started = System.nanoTime();
    String outcome = "transport_error";
    boolean acquired = false;
    try {
      acquired = bulkhead.tryAcquire(properties.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        outcome = "bulkhead_rejected";
        throw new GatewayRequestNotSentException(
            "FreedomPay concurrency limit reached for " + operation, null);
      }
      String body = send(path, form);
      FreedomPayMessage message = FreedomPayXmlParser.parseMessage(body);
      FreedomPayResponse response = verify(operation, script, message, secret);
      outcome = response.signed() ? "ok" : "unsigned_error";
      if (response.signed() && "error".equalsIgnoreCase(response.status())) {
        outcome = "provider_error";
      }
      return response;
    } catch (FreedomPaySignatureException ex) {
      outcome = "invalid_signature";
      throw ex;
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new FreedomPayException("FreedomPay call interrupted: " + operation, ex);
    } finally {
      if (acquired) {
        bulkhead.release();
      }
      record(operation, outcome, System.nanoTime() - started);
    }
  }

  private String send(String path, Map<String, String> form) throws InterruptedException {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create(stripTrailingSlash(properties.getBaseUrl()) + path))
            .timeout(Duration.ofMillis(Math.max(1_000, properties.getReadTimeoutMs())))
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("Accept", "application/xml, text/xml")
            .POST(HttpRequest.BodyPublishers.ofString(encode(form), StandardCharsets.UTF_8))
            .build();
    HttpResponse<InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (HttpConnectTimeoutException | ConnectException ex) {
      throw new GatewayRequestNotSentException("FreedomPay connection could not be opened", ex);
    } catch (HttpTimeoutException ex) {
      throw new FreedomPayException("FreedomPay response timed out", ex);
    } catch (IOException ex) {
      throw new FreedomPayException(
          "FreedomPay transport failure: " + ex.getClass().getSimpleName(), ex);
    }
    try (InputStream in = response.body()) {
      byte[] bytes = in.readNBytes(properties.getMaxResponseBytes() + 1);
      if (bytes.length > properties.getMaxResponseBytes()) {
        throw new FreedomPayException("FreedomPay response exceeds size limit");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new FreedomPayException("FreedomPay HTTP status " + response.statusCode());
      }
      return new String(bytes, StandardCharsets.UTF_8);
    } catch (IOException ex) {
      throw new FreedomPayException("FreedomPay response could not be read", ex);
    }
  }

  private FreedomPayResponse verify(
      String operation, String script, FreedomPayMessage message, String secret) {
    Map<String, String> fields = message.firstValues();
    boolean hasSignature = message.count(FreedomPaySignatureService.SIGNATURE_FIELD) > 0;
    if (!properties.isVerifyResponseSignatures()) {
      return new FreedomPayResponse(message, fields, true);
    }
    if (hasSignature) {
      if (!signatureService.verify(script, message, secret)) {
        invalidSignature(operation, "mismatch");
        throw new FreedomPaySignatureException(
            "FreedomPay response signature is invalid for " + operation);
      }
      return new FreedomPayResponse(message, fields, true);
    }
    if ("error".equalsIgnoreCase(fields.getOrDefault("pg_status", ""))) {
      // Unsigned errors are tolerated only as errors (merchant not identified, 9998/101, and the
      // many documented error examples without pg_sig). They never produce success.
      return new FreedomPayResponse(message, fields, false);
    }
    invalidSignature(operation, "missing");
    throw new FreedomPaySignatureException(
        "FreedomPay response for " + operation + " is not signed");
  }

  private void invalidSignature(String operation, String reason) {
    log.warn("FreedomPay {} response rejected: signature {}", operation, reason);
    MeterRegistry registry = meterRegistry.getIfAvailable();
    if (registry != null) {
      Counter.builder("ecopay.freedompay.response.invalid_signature")
          .tag("operation", operation)
          .tag("reason", reason)
          .register(registry)
          .increment();
    }
  }

  private void record(String operation, String outcome, long nanos) {
    if (!"ok".equals(outcome)) {
      log.warn("FreedomPay {} finished with outcome {}", operation, outcome);
    }
    MeterRegistry registry = meterRegistry.getIfAvailable();
    if (registry == null) {
      return;
    }
    Timer.builder("ecopay.freedompay.request")
        .tag("operation", operation)
        .tag("outcome", outcome)
        .publishPercentileHistogram()
        .register(registry)
        .record(Duration.ofNanos(nanos));
  }

  private static void sleepBackoff(int attempt) {
    long base = 400L * attempt;
    long jitter = ThreadLocalRandom.current().nextLong(base / 2 + 1);
    try {
      Thread.sleep(base + jitter);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new FreedomPayException("FreedomPay retry interrupted", ex);
    }
  }

  private static String encode(Map<String, String> form) {
    StringJoiner joiner = new StringJoiner("&");
    form.forEach(
        (k, v) ->
            joiner.add(
                URLEncoder.encode(k, StandardCharsets.UTF_8)
                    + "="
                    + URLEncoder.encode(v == null ? "" : v, StandardCharsets.UTF_8)));
    return joiner.toString();
  }

  private static String stripTrailingSlash(String url) {
    String value = url == null ? "" : url.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }
}
