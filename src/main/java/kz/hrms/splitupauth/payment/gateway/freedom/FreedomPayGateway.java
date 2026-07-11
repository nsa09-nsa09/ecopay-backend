package kz.hrms.splitupauth.payment.gateway.freedom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component("freedomPayGateway")
@RequiredArgsConstructor
@Slf4j
public class FreedomPayGateway implements PaymentGateway {

  public static final String PROVIDER_NAME = "freedompay";

  private static final SecureRandom RAND = new SecureRandom();

  private final FreedomPayProperties properties;
  private final FreedomPaySignatureService signatureService;
  private final FreedomPayClient client;
  private final FreedomPayUrlResolver urlResolver;

  @Override
  public String providerName() {
    return PROVIDER_NAME;
  }

  @Override
  public GatewayChargeResponse initCharge(GatewayChargeRequest request) {
    Map<String, String> params = baseParams("init_payment.php");
    params.put(
        "pg_order_id",
        request.getOrderIdOverride() != null && !request.getOrderIdOverride().isBlank()
            ? request.getOrderIdOverride()
            : String.valueOf(request.getIntentId()));
    params.put("pg_amount", formatAmount(request.getAmount()));
    params.put("pg_currency", request.getCurrency() != null ? request.getCurrency() : "KZT");
    params.put("pg_description", nonNull(request.getDescription(), "Ecopay payment"));
    params.put("pg_user_phone", nonNull(request.getUserPhone(), ""));
    params.put("pg_user_contact_email", nonNull(request.getUserEmail(), ""));
    params.put("pg_result_url", urlResolver.resultUrl());
    params.put("pg_success_url", urlResolver.successUrl(request.getSuccessUrl()));
    params.put("pg_failure_url", urlResolver.failureUrl(request.getFailureUrl()));
    params.put("pg_request_method", "POST");
    if (request.isSaveCardRequested()) {
      params.put("pg_recurring_start", "1");
      // Mandatory when pg_recurring_start=1: how long (months) the saved-card profile stays
      // usable, and the merchant-side user id the profile is bound to.
      params.put("pg_recurring_lifetime", String.valueOf(properties.getRecurringLifetimeDays()));
      if (request.getUserId() != null && !request.getUserId().isBlank()) {
        params.put("pg_user_id", request.getUserId());
      }
    }

    String sig = signatureService.signWithMerchantSecret("init_payment.php", params);
    params.put("pg_sig", sig);

    Map<String, String> response = client.postForm("/init_payment.php", params);
    String status = response.getOrDefault("pg_status", "");
    if ("ok".equalsIgnoreCase(status)) {
      return GatewayChargeResponse.builder()
          .success(true)
          .externalPaymentId(response.get("pg_payment_id"))
          .paymentUrl(response.get("pg_redirect_url"))
          .requiresRedirect(true)
          .providerStatusCode(status)
          .build();
    }
    return GatewayChargeResponse.builder()
        .success(false)
        .providerStatusCode(status)
        .failureCode(response.get("pg_error_code"))
        .failureMessage(response.get("pg_error_description"))
        .build();
  }

  @Override
  public GatewayChargeResponse chargeWithToken(
      GatewayChargeRequest request, String savedCardToken) {
    Map<String, String> params = baseParams("recurring.php");
    params.put("pg_recurring_profile", savedCardToken);
    params.put("pg_order_id", String.valueOf(request.getIntentId()));
    params.put("pg_amount", formatAmount(request.getAmount()));
    params.put("pg_description", nonNull(request.getDescription(), "Ecopay subscription"));

    String sig = signatureService.signWithMerchantSecret("recurring.php", params);
    params.put("pg_sig", sig);

    Map<String, String> response = client.postForm("/recurring.php", params);
    String status = response.getOrDefault("pg_status", "");
    if ("ok".equalsIgnoreCase(status)) {
      return GatewayChargeResponse.builder()
          .success(true)
          .externalPaymentId(response.get("pg_payment_id"))
          .requiresRedirect(false)
          .providerStatusCode(status)
          .build();
    }
    return GatewayChargeResponse.builder()
        .success(false)
        .providerStatusCode(status)
        .failureCode(response.get("pg_error_code"))
        .failureMessage(response.get("pg_error_description"))
        .build();
  }

  @Override
  public GatewayRefundResponse refund(GatewayRefundRequest request) {
    Map<String, String> params = baseParams("revoke.php");
    params.put("pg_payment_id", request.getExternalPaymentId());
    if (request.getAmount() != null) {
      params.put("pg_refund_amount", formatAmount(request.getAmount()));
    }

    String sig = signatureService.signWithMerchantSecret("revoke.php", params);
    params.put("pg_sig", sig);

    Map<String, String> response = client.postForm("/revoke.php", params);
    String status = response.getOrDefault("pg_status", "");
    boolean ok = "ok".equalsIgnoreCase(status);
    return GatewayRefundResponse.builder()
        .success(ok)
        .externalRefundId(response.get("pg_refund_id"))
        .providerStatusCode(status)
        .failureCode(ok ? null : response.get("pg_error_code"))
        .failureMessage(ok ? null : response.get("pg_error_description"))
        .pending(!ok && "pending".equalsIgnoreCase(status))
        .build();
  }

  @Override
  public GatewayPayoutResponse payout(GatewayPayoutRequest request) {
    Map<String, String> params = baseParams("payouts.php");
    params.put("pg_amount", formatAmount(request.getAmount()));
    params.put("pg_card_token", request.getDestinationCardToken());
    params.put("pg_order_id", String.valueOf(request.getPayoutId()));
    params.put("pg_description", nonNull(request.getDescription(), "Ecopay payout"));
    params.put("pg_result_url", urlResolver.payoutResultUrl());

    String sig = signatureService.signWithPayoutSecret("payouts.php", params);
    params.put("pg_sig", sig);

    Map<String, String> response = client.postForm("/payouts.php", params);
    String status = response.getOrDefault("pg_status", "");
    boolean ok = "ok".equalsIgnoreCase(status);
    return GatewayPayoutResponse.builder()
        .success(ok)
        .externalPayoutId(response.get("pg_payout_id"))
        .providerStatusCode(status)
        .failureCode(ok ? null : response.get("pg_error_code"))
        .failureMessage(ok ? null : response.get("pg_error_description"))
        .pending(!ok && "pending".equalsIgnoreCase(status))
        .build();
  }

  @Override
  public GatewayStatusResponse getStatus(String externalPaymentId) {
    Map<String, String> params = baseParams("get_status.php");
    params.put("pg_payment_id", externalPaymentId);

    String sig = signatureService.signWithMerchantSecret("get_status.php", params);
    params.put("pg_sig", sig);

    Map<String, String> response = client.postForm("/get_status.php", params);
    // Diagnostic: which fields Freedom Pay returns for a saved-card status query (token may
    // only arrive via the result webhook, not here). Values omitted — keys + presence only.
    log.info(
        "FreedomPay get_status[{}] keys={} txStatus={} hasProfileId={} hasCardToken={} hasCardId={}",
        externalPaymentId,
        response.keySet(),
        response.getOrDefault("pg_transaction_status", response.get("pg_payment_status")),
        response.get("pg_recurring_profile_id") != null,
        response.get("pg_card_token") != null,
        response.get("pg_card_id") != null);
    // Sandbox-confirmed: the payment state arrives in pg_transaction_status
    // (partial | pending | ok | failed | revoked | new), NOT pg_payment_status.
    String paymentStatus =
        response
            .getOrDefault("pg_transaction_status", response.getOrDefault("pg_payment_status", ""))
            .toUpperCase();
    String mapped =
        switch (paymentStatus) {
          case "OK", "SUCCESS" -> "SUCCESS";
          case "FAILED", "ERROR", "REJECTED", "REVOKED" -> "FAILED";
          default -> "PENDING"; // partial | pending | new | incomplete
        };
    return GatewayStatusResponse.builder()
        .externalPaymentId(externalPaymentId)
        .status(mapped)
        .providerStatusCode(paymentStatus)
        .cardPanMask(response.get("pg_card_pan"))
        // Saved-card reuse token: Freedom Pay returns the recurring profile id for
        // card-save flows (pg_recurring_profile_id); fall back to card token / id.
        .cardToken(
            firstNonBlank(
                response.get("pg_recurring_profile_id"),
                response.get("pg_recurring_profile"),
                response.get("pg_card_token"),
                response.get("pg_card_id")))
        .build();
  }

  /**
   * Look up a user's most recently saved card via the cardstorage/list API. Freedom Pay returns the
   * reusable token (pg_recurring_profile_id) here keyed by pg_user_id, so this lets us obtain it
   * without the result webhook (which can't reach localhost). Returns null if none/unavailable.
   */
  public GatewayStatusResponse fetchSavedCardForUser(String userId) {
    if (userId == null || userId.isBlank()) return null;
    String merchantId = properties.getMerchantId();
    String path = "/v1/merchant/" + merchantId + "/cardstorage/list";

    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_merchant_id", merchantId);
    params.put("pg_user_id", userId);
    params.put("pg_salt", randomSalt());
    // FreedomPay cardstorage endpoints sign with just the operation name ("list"), not the URL
    // path — confirmed against docs.freedompay.kz's cardstorage/list signature example.
    String script = "list";
    params.put("pg_sig", signatureService.signWithMerchantSecret(script, params));

    String xml;
    try {
      xml = client.postFormRaw(path, params);
    } catch (Exception ex) {
      log.warn("cardstorage/list failed for user {}: {}", userId, ex.getMessage());
      return null;
    }
    log.info("FreedomPay cardstorage/list[user={}] raw response: {}", userId, xml);

    java.util.List<Map<String, String>> cards = FreedomPayXmlParser.parseCardList(xml);
    Map<String, String> best = null;
    String bestCreatedAt = "";
    for (Map<String, String> c : cards) {
      String token =
          firstNonBlank(
              c.get("pg_recurring_profile_id"), c.get("pg_card_token"), c.get("pg_card_id"));
      if (token == null) continue;
      String created = c.getOrDefault("created_at", "");
      // ISO-8601 timestamps compare lexicographically; keep the newest card.
      if (best == null || created.compareTo(bestCreatedAt) >= 0) {
        best = c;
        bestCreatedAt = created;
      }
    }
    if (best == null) return null;
    return GatewayStatusResponse.builder()
        .status("SUCCESS")
        .cardToken(
            firstNonBlank(
                best.get("pg_recurring_profile_id"),
                best.get("pg_card_token"),
                best.get("pg_card_id")))
        .cardPanMask(firstNonBlank(best.get("pg_card_hash"), best.get("pg_card_pan")))
        .build();
  }

  @Override
  public GatewayWebhookEvent verifyAndParseWebhook(Map<String, String> params) {
    return verifyAndParseWebhook(scriptNameFromUrl(properties.getResultUrl(), "result"), params);
  }

  /**
   * Freedom Pay signs callbacks with the LAST path segment of the merchant's result URL as the
   * script name (e.g. {@code result}, {@code payout-result}).
   */
  public GatewayWebhookEvent verifyAndParseWebhook(String script, Map<String, String> params) {
    boolean valid = verifyWebhookSignature(script, params);

    String paymentId = params.get("pg_payment_id");
    String salt = params.get("pg_salt");
    String requestId =
        paymentId != null
            ? paymentId + ":" + (salt == null ? "" : salt)
            : "no-payment-id:" + System.currentTimeMillis();

    // pg_result: 1 = success, 0 = failure, 2 = not completed yet.
    String resultRaw = params.get("pg_result");
    String resultStatus =
        switch (nonNull(resultRaw, "")) {
          case "1" -> "SUCCESS";
          case "0" -> "FAILED";
          default -> "PENDING";
        };
    if ("REFUND".equals(params.get("pg_event_type")) || params.get("pg_refund_id") != null) {
      // Refund result callback (async). NOTE: confirm exact Freedom Pay refund
      // callback param names against the live provider spec before relying on it.
      return GatewayWebhookEvent.builder()
          .kind("REFUND")
          .resultStatus(resultStatus)
          .externalPaymentId(params.get("pg_refund_id"))
          .providerRequestId(requestId)
          .signature(params.get("pg_sig"))
          .rawParams(params)
          .build();
    }
    if ("PAYOUT".equals(params.get("pg_event_type")) || params.get("pg_payout_id") != null) {
      // Payout webhook
      return GatewayWebhookEvent.builder()
          .kind("PAYOUT")
          .resultStatus(resultStatus)
          .externalPaymentId(params.get("pg_payout_id"))
          .providerRequestId(requestId)
          .signature(params.get("pg_sig"))
          .rawParams(params)
          .build();
    }

    Long intentId = parseLongOrNull(params.get("pg_order_id"));
    BigDecimal amount = parseAmount(params.get("pg_amount"));

    return GatewayWebhookEvent.builder()
        .kind("CHARGE")
        .resultStatus(resultStatus)
        .intentId(intentId)
        .externalPaymentId(paymentId)
        .amount(amount)
        .currency(params.getOrDefault("pg_currency", "KZT"))
        .providerStatusCode(params.get("pg_payment_status"))
        .failureCode(params.get("pg_error_code"))
        .failureMessage(params.get("pg_error_description"))
        .cardPanMask(params.get("pg_card_pan"))
        .cardToken(
            firstNonBlank(
                params.get("pg_recurring_profile_id"),
                params.get("pg_recurring_profile"),
                params.get("pg_card_token"),
                params.get("pg_card_id")))
        .rawParams(params)
        .signature(params.get("pg_sig"))
        .providerRequestId(valid ? requestId : "INVALID:" + requestId)
        .build();
  }

  public boolean verifyWebhookSignature(String script, Map<String, String> params) {
    return script.contains("payout")
        ? signatureService.verifyWithPayoutSecret(script, params)
        : signatureService.verifyWithMerchantSecret(script, params);
  }

  /**
   * Builds the signed XML reply Freedom Pay expects on its result callbacks (pg_salt + pg_sig are
   * mandatory in the merchant response).
   */
  public String buildWebhookResponse(String script, String status, String description) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("pg_status", status);
    p.put("pg_description", description);
    p.put("pg_salt", randomSalt());
    String sig =
        script.contains("payout")
            ? signatureService.signWithPayoutSecret(script, p)
            : signatureService.signWithMerchantSecret(script, p);
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?><response>"
        + "<pg_status>"
        + status
        + "</pg_status>"
        + "<pg_description>"
        + description
        + "</pg_description>"
        + "<pg_salt>"
        + p.get("pg_salt")
        + "</pg_salt>"
        + "<pg_sig>"
        + sig
        + "</pg_sig></response>";
  }

  private static String scriptNameFromUrl(String url, String fallback) {
    if (url == null || url.isBlank()) return fallback;
    String path = url;
    int q = path.indexOf('?');
    if (q >= 0) path = path.substring(0, q);
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    int slash = path.lastIndexOf('/');
    String last = slash >= 0 ? path.substring(slash + 1) : path;
    return last.isBlank() ? fallback : last;
  }

  private Map<String, String> baseParams(String script) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("pg_merchant_id", properties.getMerchantId());
    p.put("pg_salt", randomSalt());
    if ("1".equals(properties.getTestMode())) {
      p.put("pg_testing_mode", "1");
    }
    return p;
  }

  private static String randomSalt() {
    byte[] buf = new byte[8];
    RAND.nextBytes(buf);
    StringBuilder sb = new StringBuilder();
    for (byte b : buf) sb.append(String.format("%02x", b));
    return sb.toString();
  }

  private static String formatAmount(BigDecimal amount) {
    if (amount == null) return "0.00";
    return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static BigDecimal parseAmount(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return new BigDecimal(s).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Long parseLongOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Long.parseLong(s);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String nonNull(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  /** First non-blank value, or null if all are blank. */
  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }
}
