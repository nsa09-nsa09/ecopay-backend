package kz.hrms.splitupauth.payment.gateway.freedom;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Low-level HTTP client for Freedom Pay (PayBox) classic API.
 *
 * <p>Endpoints used: - POST /init_payment.php — create one-time payment, get redirect URL - POST
 * /v1/merchant/{id}/payment/init — modern API alternative - POST
 * /v1/merchant/{id}/payment/{ext_id}/cancel - POST /v1/merchant/{id}/payment/{ext_id}/refund - POST
 * /v1/merchant/{id}/payouts — payout to card - POST /get_status.php — query payment status
 *
 * <p>The transport uses an Apache HttpClient 5 backed request factory with a <b>bounded</b> pooling
 * connection manager and explicit connect/socket timeouts (see {@link FreedomPayProperties}).
 * Without timeouts a hung provider response would pin a Tomcat worker thread forever.
 *
 * <p><b>Retry policy (money-safety):</b> {@link #postForm} and {@link #postFormRaw} never retry —
 * they back the money-moving POSTs (init_payment, recurring, revoke, reg2reg, cardstorage/add2), so
 * an ambiguous socket timeout can never spawn a second financial operation; it surfaces as a failure
 * and is reconciled by status. The {@code *Retryable} variants add a bounded, jittered retry and are
 * used ONLY by read-only status/list lookups, which are safe to repeat.
 */
@Component
@Slf4j
public class FreedomPayClient {

  private final FreedomPayProperties properties;
  private RestClient restClient;

  public FreedomPayClient(FreedomPayProperties properties) {
    this.properties = properties;
  }

  @PostConstruct
  public void initRestClient() {
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(properties.getConnectTimeoutMs()))
            .setSocketTimeout(Timeout.ofMilliseconds(properties.getReadTimeoutMs()))
            .build();

    PoolingHttpClientConnectionManager connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDefaultConnectionConfig(connectionConfig)
            .setMaxConnTotal(properties.getMaxConnections())
            .setMaxConnPerRoute(properties.getMaxConnectionsPerRoute())
            .build();

    RequestConfig requestConfig =
        RequestConfig.custom()
            .setConnectionRequestTimeout(
                Timeout.ofMilliseconds(properties.getConnectionRequestTimeoutMs()))
            .setResponseTimeout(Timeout.ofMilliseconds(properties.getReadTimeoutMs()))
            .build();

    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictExpiredConnections()
            .build();

    this.restClient =
        RestClient.builder()
            .baseUrl(properties.getBaseUrl())
            .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
            .build();
  }

  /** Low-level form POST returning the raw response body. Throws on any transport/HTTP error. */
  private String exchange(String path, Map<String, String> params) {
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    params.forEach(form::add);
    return restClient
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
        .accept(MediaType.APPLICATION_XML, MediaType.TEXT_XML)
        .body(form)
        .retrieve()
        .body(String.class);
  }

  /**
   * Send form-urlencoded params and parse XML response into a map. <b>Never retried</b> — safe for
   * money-moving POSTs.
   */
  public Map<String, String> postForm(String path, Map<String, String> params) {
    try {
      return FreedomPayXmlParser.parseFlatXml(exchange(path, params));
    } catch (Exception ex) {
      log.error("Freedom Pay request to {} failed: {}", path, ex.getMessage());
      throw new FreedomPayException("Freedom Pay request failed: " + ex.getMessage(), ex);
    }
  }

  /**
   * Send form-urlencoded params and return the raw XML body (for endpoints whose response is a
   * list, e.g. cardstorage/list, which the flat-map parser can't represent). <b>Never retried.</b>
   */
  public String postFormRaw(String path, Map<String, String> params) {
    try {
      return exchange(path, params);
    } catch (Exception ex) {
      log.error("Freedom Pay request to {} failed: {}", path, ex.getMessage());
      throw new FreedomPayException("Freedom Pay request failed: " + ex.getMessage(), ex);
    }
  }

  /**
   * Read-only variant of {@link #postForm} with bounded, jittered retry on transient transport
   * errors. Use ONLY for idempotent status/lookup calls — never for money-moving POSTs.
   */
  public Map<String, String> postFormRetryable(String path, Map<String, String> params) {
    return runWithRetry(path, () -> FreedomPayXmlParser.parseFlatXml(exchange(path, params)));
  }

  /** Read-only, retryable counterpart of {@link #postFormRaw}. */
  public String postFormRawRetryable(String path, Map<String, String> params) {
    return runWithRetry(path, () -> exchange(path, params));
  }

  private <T> T runWithRetry(String path, Supplier<T> op) {
    int maxAttempts = 1 + Math.max(0, properties.getStatusRetryMaxAttempts());
    FreedomPayException last = null;
    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        return op.get();
      } catch (Exception ex) {
        last =
            (ex instanceof FreedomPayException fpe)
                ? fpe
                : new FreedomPayException("Freedom Pay request failed: " + ex.getMessage(), ex);
        if (attempt >= maxAttempts || !isTransient(ex)) {
          break;
        }
        sleepWithJitter(attempt);
        log.warn(
            "Retrying read-only Freedom Pay call to {} (attempt {}/{}) after transient error: {}",
            path,
            attempt + 1,
            maxAttempts,
            ex.getMessage());
      }
    }
    log.error("Freedom Pay read-only request to {} failed: {}", path, last.getMessage());
    throw last;
  }

  /** Transient = a transport/IO failure (timeout, connection reset). Parse errors are NOT retried. */
  private boolean isTransient(Throwable ex) {
    for (Throwable t = ex; t != null; t = t.getCause()) {
      if (t instanceof IOException) {
        return true;
      }
      if (t == t.getCause()) {
        break;
      }
    }
    return false;
  }

  private void sleepWithJitter(int attempt) {
    long base = (long) properties.getStatusRetryBackoffMs() * attempt;
    long jitter = ThreadLocalRandom.current().nextLong(0, Math.max(1, base / 2 + 1));
    try {
      Thread.sleep(base + jitter);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
      throw new FreedomPayException("Interrupted during Freedom Pay retry backoff", ie);
    }
  }
}
