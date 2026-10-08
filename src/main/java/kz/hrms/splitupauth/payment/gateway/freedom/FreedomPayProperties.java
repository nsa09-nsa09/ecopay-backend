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
  private String payoutCardResultUrl = "";
  private String payoutResultUrl = "";
  private String successUrl = "";
  private String failureUrl = "";

  /**
   * HTTP method FreedomPay uses when returning the browser to {@code pg_success_url} ({@code
   * pg_success_url_method}). docs.freedompay.kz "Create payment" lists only {@code GET} and {@code
   * POST} (default {@code GET}); the frontend's /payment/confirmation route is only reachable by
   * GET (a POST return is answered 405 by the SPA's nginx), so the default stays {@code GET}.
   */
  private String successUrlMethod = "GET";

  /**
   * HTTP method for the failure return ({@code pg_failure_url_method}); see {@link
   * #successUrlMethod}.
   */
  private String failureUrlMethod = "GET";

  /** "1" = sandbox/test mode, "0" = real charges. */
  private String testMode = "1";

  /**
   * {@code pg_auto_clearing}. FreedomPay defaults to TWO-step ('0': authorize/hold, clear later,
   * auto-cleared after up to 5 days). EcoPay treats a successful member payment as captured money,
   * so it requests one-step clearing explicitly. Authorization/capture is the provider's card
   * state; EcoPay's 30-day owner payout reserve ({@code app.payout.hold-days}) is unrelated to it.
   */
  private boolean autoClearing = true;

  /**
   * {@code pg_recurring_lifetime} — in MONTHS (legacy docs: "minimum 1 (1 month)"). The current
   * docs cap it at 12 (legacy allowed 156), so values are clamped to 1..12. Required by FreedomPay
   * whenever {@code pg_recurring_start=1}.
   */
  private int recurringLifetimeMonths = 12;

  /**
   * Provider-side payment page lifetime ({@code pg_lifetime}, seconds). Aligned with the 30-minute
   * EcoPay payment intent/seat reservation so a member cannot pay long after the seat was released.
   */
  private int paymentLifetimeSeconds = 1800;

  /** Use legacy {@code .php} script URLs for revoke/cancel/recurring instead of current paths. */
  private boolean legacyPhpEndpoints = false;

  /**
   * Script name signed for {@code /cardstoragepayout/add}: {@code add} (last path segment).
   * Verified against the sandbox on 2026-10-06 ({@code add2} is rejected with error 1100).
   */
  private String payoutCardStorageScript = "add";

  /**
   * Sign the payout-card tokenization with the payout secret instead of the merchant (payment)
   * secret. The sandbox accepts only the merchant secret for {@code cardstoragepayout/add} (payout
   * secret = error 1100, verified 2026-10-06), so the default is false.
   */
  private boolean payoutCardStorageUsesPayoutSecret = false;

  /** Verify {@code pg_sig} on every provider response. Must stay true in production. */
  private boolean verifyResponseSignatures = true;

  private int connectTimeoutMs = 5_000;
  private int readTimeoutMs = 20_000;

  /** Bulkhead: maximum simultaneous in-flight FreedomPay requests per application instance. */
  private int maxConcurrentRequests = 16;

  /** How long a caller may wait for a bulkhead slot before the call fails as not-sent. */
  private int acquireTimeoutMs = 2_000;

  private int maxResponseBytes = 256 * 1024;

  /** Bounded retries for READ-ONLY calls (status/list). Money-moving calls are never retried. */
  private int readRetries = 2;

  /**
   * Payout-card callback URL. When not configured explicitly it is derived from the result URL
   * (same host, last segment {@code payout-card-result}), so existing deployments that already set
   * FREEDOMPAY_RESULT_URL get a correct public callback without a new variable.
   */
  public String getPayoutCardResultUrl() {
    if (payoutCardResultUrl != null && !payoutCardResultUrl.isBlank()) {
      return payoutCardResultUrl;
    }
    if (resultUrl == null || resultUrl.isBlank()) {
      return "";
    }
    String base = resultUrl.trim();
    int q = base.indexOf('?');
    if (q >= 0) base = base.substring(0, q);
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    int slash = base.lastIndexOf('/');
    return slash < 0 ? "" : base.substring(0, slash) + "/payout-card-result";
  }
}
