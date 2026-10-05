package kz.hrms.splitupauth.payment.gateway.freedom;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "ecopay.payments.freedompay")
public class FreedomPayProperties {

  private String baseUrl = "https://test-api.freedompay.kz";
  private String merchantId = "";
  private String secretKey = "";
  private String payoutSecretKey = "";
  private String resultUrl = "";
  private String cardStorageResultUrl = "";
  private String payoutResultUrl = "";
  private String successUrl = "";
  private String failureUrl = "";

  /** "1" = sandbox/test mode, "0" = real charges. */
  private String testMode = "1";

  /**
   * Lifetime of a saved-card recurring profile. Freedom Pay REQUIRES pg_recurring_lifetime whenever
   * pg_recurring_start=1, and caps it at 156, so this is sent on every card-save init_payment and
   * must stay &lt;= 156.
   */
  private int recurringLifetimeDays = 156;

  // --- HTTP client timeouts / pool -------------------------------------------
  // Without these a hung provider response pins a Tomcat worker thread (and, with
  // open-in-view=false, briefly a DB connection on the surrounding transaction)
  // indefinitely. All overridable via ecopay.payments.freedompay.* properties.

  /** TCP connect timeout for provider calls. */
  private int connectTimeoutMs = 5000;

  /** Socket read / response timeout. A provider that accepts the connection but never replies is
   * abandoned after this. */
  private int readTimeoutMs = 20000;

  /** Max time to wait for a free connection from the bounded pool before failing fast. */
  private int connectionRequestTimeoutMs = 5000;

  /** Hard ceiling on total pooled connections to the provider. */
  private int maxConnections = 50;

  /** Per-route (per host) connection ceiling. */
  private int maxConnectionsPerRoute = 20;

  /** Bounded retry attempts for READ-ONLY status calls only (total tries = 1 + this). Money-moving
   * POSTs are never retried, so a socket timeout can never create a second financial operation. */
  private int statusRetryMaxAttempts = 2;

  /** Base backoff between read-only status retries; actual delay adds random jitter on top. */
  private int statusRetryBackoffMs = 200;
}
