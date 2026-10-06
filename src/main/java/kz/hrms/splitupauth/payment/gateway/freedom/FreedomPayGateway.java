package kz.hrms.splitupauth.payment.gateway.freedom;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayRequestNotSentException;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.PaymentGateway;
import kz.hrms.splitupauth.payment.gateway.ProviderPaymentState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * FreedomPay Merchant API adapter, aligned with docs.freedompay.kz (2026) — see
 * docs/payments/freedompay-contract-audit.md for the per-endpoint audit.
 *
 * <p>Every call goes through {@link FreedomPayClient}, which signs the request and rejects any
 * response whose {@code pg_sig} does not verify. Script names are always derived from the called
 * path (last segment), so the signed script can never drift from the URL.
 */
@Component("freedomPayGateway")
@RequiredArgsConstructor
@Slf4j
public class FreedomPayGateway implements PaymentGateway {

  public static final String PROVIDER_NAME = "freedompay";

  /** Callback scripts — the last path segment of each webhook URL (FreedomPayWebhookController). */
  public static final String RESULT_SCRIPT = "result";

  public static final String CARD_STORAGE_RESULT_SCRIPT = "card-storage-result";
  public static final String PAYOUT_CARD_RESULT_SCRIPT = "payout-card-result";
  public static final String PAYOUT_RESULT_SCRIPT = "payout-result";

  static final String INIT_PAYMENT_PATH = "/init_payment.php";
  static final String STATUS_PATH = "/get_status3.php";
  static final String PAYOUT_PATH = "/api/reg2reg";
  static final String PAYOUT_STATUS_PATH = "/api/payment_status2";

  /** Provider error codes meaning "no such payment/order". */
  private static final Set<String> NOT_FOUND_CODES = Set.of("11068", "340");

  private static final SecureRandom RAND = new SecureRandom();

  private final FreedomPayProperties properties;
  private final FreedomPaySignatureService signatureService;
  private final FreedomPayClient client;
  private final FreedomPayUrlResolver urlResolver;

  @Override
  public String providerName() {
    return PROVIDER_NAME;
  }

  // ---------------------------------------------------------------- payout card tokenization

  /**
   * Starts FreedomPay's PAYOUT card tokenization ({@code /cardstoragepayout/add}, Merchant API →
   * Payout → Card token → Tokenize card). Cards saved this way "can only be used for payouts",
   * which is exactly what reg2reg needs; a purchase/recurring token ({@code cardstorage/add2},
   * {@code pg_recurring_profile_id}) is never assumed to be payout-compatible. The endpoint takes
   * no order id, so the callback is matched to the binding through the returned {@code
   * pg_payment_id}.
   */
  @Override
  public GatewayCardBindingResponse initCardBinding(GatewayCardBindingRequest request) {
    String merchantId = properties.getMerchantId();
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_merchant_id", merchantId);
    params.put("pg_user_id", request.getUserId());
    params.put("pg_post_link", urlResolver.payoutCardResultUrl());
    params.put("pg_back_link", request.getBackUrl());
    params.put("pg_salt", randomSalt());

    FreedomPayResponse response =
        client.call(
            "payout_card_add",
            "/v1/merchant/" + merchantId + "/cardstoragepayout/add",
            properties.getPayoutCardStorageScript(),
            params,
            payoutCardSecret(),
            false);
    String paymentId = response.get("pg_payment_id");
    if (response.isOk() && notBlank(paymentId) && notBlank(response.get("pg_redirect_url"))) {
      return GatewayCardBindingResponse.builder()
          .success(true)
          .externalBindingId(paymentId)
          .redirectUrl(response.get("pg_redirect_url"))
          .requiresRedirect(true)
          .providerStatusCode(response.status())
          .build();
    }
    return GatewayCardBindingResponse.builder()
        .success(false)
        .providerStatusCode(response.status())
        .failureCode(response.get("pg_error_code"))
        .failureMessage(response.get("pg_error_description"))
        .build();
  }

  // ---------------------------------------------------------------- purchase

  @Override
  public GatewayChargeResponse initCharge(GatewayChargeRequest request) {
    Map<String, String> params = baseParams();
    params.put(
        "pg_order_id",
        notBlank(request.getOrderIdOverride())
            ? request.getOrderIdOverride()
            : String.valueOf(request.getIntentId()));
    params.put("pg_amount", formatAmount(request.getAmount()));
    params.put("pg_idempotency_key", request.getIdempotencyKey());
    params.put("pg_currency", request.getCurrency() != null ? request.getCurrency() : "KZT");
    params.put("pg_description", nonNull(request.getDescription(), "EcoPay payment"));
    params.put("pg_user_phone", nonNull(request.getUserPhone(), ""));
    params.put("pg_user_contact_email", nonNull(request.getUserEmail(), ""));
    // One-step: FreedomPay's default is two-step (hold, auto-cleared after up to 5 days), but a
    // successful EcoPay member payment must mean captured money.
    params.put("pg_auto_clearing", properties.isAutoClearing() ? "1" : "0");
    params.put(
        "pg_lifetime", String.valueOf(Math.max(300, properties.getPaymentLifetimeSeconds())));
    params.put("pg_result_url", urlResolver.resultUrl());
    params.put(
        "pg_success_url",
        appendPaymentContext(urlResolver.successUrl(request.getSuccessUrl()), request));
    params.put(
        "pg_failure_url",
        appendPaymentContext(urlResolver.failureUrl(request.getFailureUrl()), request));
    params.put("pg_request_method", "POST");
    if (request.isSaveCardRequested()) {
      params.put("pg_recurring_start", "1");
      // Mandatory with pg_recurring_start=1; unit is MONTHS, current docs allow 1..12.
      params.put("pg_recurring_lifetime", String.valueOf(recurringLifetimeMonths()));
      if (notBlank(request.getUserId())) {
        params.put("pg_user_id", request.getUserId());
      }
    }

    FreedomPayResponse response =
        client.call(
            "init_payment",
            INIT_PAYMENT_PATH,
            script(INIT_PAYMENT_PATH),
            params,
            merchantSecret(),
            false);
    if (response.isOk() && notBlank(response.get("pg_redirect_url"))) {
      return GatewayChargeResponse.builder()
          .success(true)
          .externalPaymentId(response.get("pg_payment_id"))
          .paymentUrl(response.get("pg_redirect_url"))
          .requiresRedirect(true)
          .providerStatusCode(response.status())
          .build();
    }
    return failedCharge(response);
  }

  /**
   * Recurring charge with a saved purchase profile ({@code /make_recurring_payment}). The
   * synchronous {@code ok} only means FreedomPay accepted the request — the docs do not state that
   * money is captured at that point — so the result is returned as accepted-but-unconfirmed and
   * finalized by the signed result callback or a status query.
   */
  @Override
  public GatewayChargeResponse chargeWithToken(
      GatewayChargeRequest request, String savedCardToken) {
    String path =
        properties.isLegacyPhpEndpoints()
            ? "/make_recurring_payment.php"
            : "/make_recurring_payment";
    Map<String, String> params = baseParams();
    params.put("pg_recurring_profile", savedCardToken);
    params.put("pg_order_id", String.valueOf(request.getIntentId()));
    params.put("pg_amount", formatAmount(request.getAmount()));
    params.put("pg_description", nonNull(request.getDescription(), "EcoPay subscription"));
    params.put("pg_result_url", urlResolver.resultUrl());
    params.put("pg_request_method", "POST");
    params.put("pg_idempotency_key", request.getIdempotencyKey());

    FreedomPayResponse response =
        client.call("recurring_payment", path, script(path), params, merchantSecret(), false);
    if (response.isOk()) {
      return GatewayChargeResponse.builder()
          .success(true)
          .externalPaymentId(response.get("pg_payment_id"))
          .requiresRedirect(false)
          .captureConfirmed(false)
          .providerStatusCode(response.status())
          .build();
    }
    return failedCharge(response);
  }

  // ---------------------------------------------------------------- refund / cancel

  /**
   * Returns money for a payment. A two-step payment that is still only AUTHORIZED is CANCELLED
   * (full amount only); a captured payment is REFUNDED via revoke. The provider state is read
   * first; if that read fails nothing was sent, so the refund is reported as not-sent (safe to
   * retry). FreedomPay's revoke/cancel {@code ok} is treated as accepted, not settled: the caller
   * keeps the refund PENDING_PROVIDER until the status query shows the refunded amount.
   */
  @Override
  public GatewayRefundResponse refund(GatewayRefundRequest request) {
    GatewayStatusResponse current;
    try {
      current = getStatus(request.getExternalPaymentId());
    } catch (GatewayRequestNotSentException ex) {
      throw ex;
    } catch (RuntimeException ex) {
      throw new GatewayRequestNotSentException(
          "Refund not sent: provider state could not be read", ex);
    }

    ProviderPaymentState state = current.getProviderState();
    if (state == ProviderPaymentState.AUTHORIZED) {
      boolean full =
          request.getAmount() == null
              || current.getAmount() == null
              || request.getAmount().compareTo(current.getAmount()) >= 0;
      if (!full) {
        return GatewayRefundResponse.builder()
            .success(false)
            .providerStatusCode("authorized")
            .failureCode("PARTIAL_CANCEL_UNSUPPORTED")
            .failureMessage("Uncaptured two-step payment can only be cancelled in full")
            .build();
      }
      return cancel(request);
    }
    if (state != ProviderPaymentState.CAPTURED
        && state != ProviderPaymentState.PARTIALLY_REFUNDED) {
      return GatewayRefundResponse.builder()
          .success(false)
          .providerStatusCode(current.getProviderStatusCode())
          .failureCode("NOT_REFUNDABLE_STATE")
          .failureMessage("Provider payment state " + state + " cannot be refunded")
          .build();
    }

    String path = properties.isLegacyPhpEndpoints() ? "/revoke.php" : "/revoke";
    Map<String, String> params = baseParams();
    params.put("pg_payment_id", request.getExternalPaymentId());
    if (request.getAmount() != null) {
      params.put("pg_refund_amount", formatAmount(request.getAmount()));
    }
    params.put("pg_idempotency_key", request.getIdempotencyKey());
    FreedomPayResponse response =
        client.call("refund", path, script(path), params, merchantSecret(), false);
    return refundResponse(response);
  }

  private GatewayRefundResponse cancel(GatewayRefundRequest request) {
    String path = properties.isLegacyPhpEndpoints() ? "/cancel.php" : "/cancel";
    Map<String, String> params = baseParams();
    params.put("pg_payment_id", request.getExternalPaymentId());
    params.put("pg_idempotency_key", request.getIdempotencyKey());
    FreedomPayResponse response =
        client.call("cancel", path, script(path), params, merchantSecret(), false);
    return refundResponse(response);
  }

  private static GatewayRefundResponse refundResponse(FreedomPayResponse response) {
    String status = response.status().toLowerCase(Locale.ROOT);
    boolean accepted =
        response.signed()
            && (status.equals("ok") || status.equals("pending") || status.equals("success"));
    return GatewayRefundResponse.builder()
        .success(accepted)
        .pending(accepted)
        .externalRefundId(response.get("pg_refund_id"))
        .providerStatusCode(response.status())
        .failureCode(accepted ? null : response.get("pg_error_code"))
        .failureMessage(accepted ? null : response.get("pg_error_description"))
        .build();
  }

  // ---------------------------------------------------------------- payout

  @Override
  public GatewayPayoutResponse payout(GatewayPayoutRequest request) {
    Map<String, String> params = baseParams();
    params.put("pg_amount", formatAmount(request.getAmount()));
    params.put("pg_card_token_to", request.getDestinationCardToken());
    params.put(
        "pg_order_id",
        request.getProviderOrderId() == null
            ? String.valueOf(request.getPayoutId())
            : request.getProviderOrderId());
    params.put("pg_user_id", request.getDestinationUserId());
    params.put("pg_idempotency_key", request.getIdempotencyKey());
    params.put("pg_description", nonNull(request.getDescription(), "EcoPay payout"));
    params.put("pg_post_link", urlResolver.payoutResultUrl());
    params.put(
        "pg_order_time_limit",
        LocalDateTime.now()
            .plusMinutes(30)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));

    FreedomPayResponse response =
        client.call("payout", PAYOUT_PATH, script(PAYOUT_PATH), params, payoutSecret(), false);
    String status = response.status().toLowerCase(Locale.ROOT);
    boolean ok = response.isOk();
    boolean pending = response.signed() && (status.equals("process") || status.equals("pending"));
    log.info(
        "FreedomPay reg2reg response: status={} code={} hasPaymentId={}",
        status,
        response.get("pg_error_code"),
        notBlank(response.get("pg_payment_id")));
    return GatewayPayoutResponse.builder()
        .success(ok)
        .externalPayoutId(response.get("pg_payment_id"))
        .providerStatusCode(response.status())
        .failureCode(ok || pending ? null : response.get("pg_error_code"))
        .failureMessage(ok || pending ? null : response.get("pg_error_description"))
        .pending(pending)
        .build();
  }

  // ---------------------------------------------------------------- status

  @Override
  public GatewayStatusResponse getStatus(String externalPaymentId) {
    Map<String, String> params = baseParams();
    params.put("pg_payment_id", externalPaymentId);
    return mapPaymentStatus(externalPaymentId, queryStatus(params));
  }

  /** get_status3 by order id returns the LAST payment created with that order id. */
  @Override
  public GatewayStatusResponse getStatusByOrderId(String merchantOrderId) {
    Map<String, String> params = baseParams();
    params.put("pg_order_id", merchantOrderId);
    return mapPaymentStatus(null, queryStatus(params));
  }

  private FreedomPayResponse queryStatus(Map<String, String> params) {
    return client.call("status", STATUS_PATH, script(STATUS_PATH), params, merchantSecret(), true);
  }

  static GatewayStatusResponse mapPaymentStatus(String requestedId, FreedomPayResponse response) {
    if (!response.signed() || "error".equalsIgnoreCase(response.status())) {
      String code = response.get("pg_error_code");
      if (code != null && NOT_FOUND_CODES.contains(code.trim())) {
        return GatewayStatusResponse.builder()
            .externalPaymentId(requestedId)
            .status("PENDING")
            .providerState(ProviderPaymentState.UNKNOWN)
            .providerStatusCode("not_found")
            .failureCode(code)
            .failureMessage(response.get("pg_error_description"))
            .notFound(true)
            .build();
      }
      throw new FreedomPayException(
          "FreedomPay status query failed with code " + (code == null ? "?" : code));
    }

    String paymentStatus =
        response.getOrDefault("pg_payment_status", "").trim().toLowerCase(Locale.ROOT);
    String capturedFlag = response.get("pg_captured");
    BigDecimal amount = parseAmount(response.get("pg_amount"));
    BigDecimal refunded =
        firstAmount(
            response.get("pg_refund_amount"),
            response.get("pg_revoke_amount"),
            sumNested(response.message(), "pg_refund_payments"),
            sumNested(response.message(), "pg_revoked_payments"));

    ProviderPaymentState state =
        switch (paymentStatus) {
          case "success", "ok" ->
              "0".equals(capturedFlag)
                  ? ProviderPaymentState.AUTHORIZED
                  : ProviderPaymentState.CAPTURED;
          case "revoked", "refunded", "canceled", "cancelled" -> ProviderPaymentState.REFUNDED;
          case "failed", "error", "rejected" -> ProviderPaymentState.FAILED;
          case "incomplete" -> ProviderPaymentState.EXPIRED;
          case "pending", "partial", "new", "process" -> ProviderPaymentState.PENDING;
          default -> ProviderPaymentState.UNKNOWN;
        };
    if (state == ProviderPaymentState.CAPTURED && refunded != null && refunded.signum() > 0) {
      state =
          amount != null && refunded.compareTo(amount) >= 0
              ? ProviderPaymentState.REFUNDED
              : ProviderPaymentState.PARTIALLY_REFUNDED;
    }

    String coarse =
        switch (state) {
          case CAPTURED -> "SUCCESS";
          case FAILED, EXPIRED -> "FAILED";
          // Captured then (partly) returned while EcoPay still considered the payment open:
          // never auto-activate nor auto-fail — a human has to look at it.
          case REFUNDED, PARTIALLY_REFUNDED -> "REVIEW";
          default -> "PENDING";
        };
    return GatewayStatusResponse.builder()
        .externalPaymentId(firstNonBlank(response.get("pg_payment_id"), requestedId))
        .status(coarse)
        .providerState(state)
        .providerStatusCode(paymentStatus)
        .amount(amount)
        .currency(response.get("pg_currency"))
        .captured(capturedFlag == null ? null : "1".equals(capturedFlag))
        .clearingAmount(parseAmount(response.get("pg_clearing_amount")))
        .refundedAmount(refunded)
        .failureCode(response.get("pg_failure_code"))
        .failureMessage(response.get("pg_failure_description"))
        .cardPanMask(response.get("pg_card_pan"))
        .cardToken(
            firstNonBlank(
                response.get("pg_recurring_profile_id"), response.get("pg_recurring_profile")))
        .build();
  }

  /**
   * Payout status ({@code /api/payment_status2}). {@code pg_order_id} is mandatory and {@code
   * pg_payment_id} optional, so payouts whose submission timed out (no provider id) can still be
   * reconciled by order id.
   */
  @Override
  public GatewayStatusResponse getPayoutStatus(String externalPayoutId, String merchantOrderId) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_merchant_id", properties.getMerchantId());
    params.put("pg_order_id", merchantOrderId);
    if (notBlank(externalPayoutId)) {
      params.put("pg_payment_id", externalPayoutId);
    }
    params.put("pg_salt", randomSalt());

    FreedomPayResponse response =
        client.call(
            "payout_status",
            PAYOUT_STATUS_PATH,
            script(PAYOUT_STATUS_PATH),
            params,
            payoutSecret(),
            true);
    String requestStatus = response.status();
    if (!response.signed() || "error".equalsIgnoreCase(requestStatus)) {
      String code = response.get("pg_error_code");
      if (code != null && NOT_FOUND_CODES.contains(code.trim())) {
        return GatewayStatusResponse.builder()
            .externalPaymentId(externalPayoutId)
            .status("PENDING")
            .providerState(ProviderPaymentState.UNKNOWN)
            .providerStatusCode("not_found")
            .failureCode(code)
            .notFound(true)
            .build();
      }
      throw new FreedomPayException(
          "FreedomPay payout status query failed with code " + (code == null ? "?" : code));
    }
    String providerPaymentId = response.get("pg_payment_id");
    String paymentStatus =
        response.getOrDefault("pg_payment_status", "").trim().toLowerCase(Locale.ROOT);
    String mapped =
        switch (paymentStatus) {
          case "success" -> "SUCCESS";
          case "error" -> "FAILED";
          default -> "PENDING"; // process, missing, or a non-final provider response
        };
    return GatewayStatusResponse.builder()
        .externalPaymentId(firstNonBlank(providerPaymentId, externalPayoutId))
        .status(mapped)
        .providerState(
            switch (mapped) {
              case "SUCCESS" -> ProviderPaymentState.CAPTURED;
              case "FAILED" -> ProviderPaymentState.FAILED;
              default -> ProviderPaymentState.PENDING;
            })
        .providerStatusCode(paymentStatus.isBlank() ? requestStatus : paymentStatus)
        .amount(parseAmount(response.get("pg_amount")))
        .currency(response.get("pg_currency"))
        // "payment_id = 0 … the payment does not exist in our system"
        .notFound("0".equals(providerPaymentId == null ? null : providerPaymentId.trim()))
        .failureCode(response.get("pg_error_code"))
        .failureMessage(response.get("pg_error_description"))
        .build();
  }

  // ---------------------------------------------------------------- callbacks

  /** Flattens a (possibly multi-valued) callback, unwrapping a single {@code pg_xml} payload. */
  public FreedomPayMessage callbackMessage(Map<String, List<String>> params) {
    List<String> xml = params.get("pg_xml");
    if (xml != null && xml.size() == 1 && params.size() == 1) {
      return FreedomPayXmlParser.parseMessage(xml.get(0));
    }
    return FreedomPayMessage.ofMulti(params);
  }

  @Override
  public GatewayWebhookEvent verifyAndParseWebhook(Map<String, String> params) {
    return verifyAndParseWebhook(RESULT_SCRIPT, params);
  }

  public GatewayWebhookEvent verifyAndParseWebhook(String script, Map<String, String> params) {
    return parseCallback(script, FreedomPayMessage.of(params));
  }

  public boolean verifyWebhookSignature(String script, Map<String, String> params) {
    return verifyCallback(script, FreedomPayMessage.of(params));
  }

  /** Callback signatures use the last segment of our callback URL as script name. */
  public boolean verifyCallback(String script, FreedomPayMessage message) {
    return signatureService.verify(script, message, callbackSecret(script));
  }

  /** Parses an already verified callback into a provider-neutral event. */
  public GatewayWebhookEvent parseCallback(String script, FreedomPayMessage message) {
    Map<String, String> params = message.firstValues();
    String requestId = callbackRequestId(script, message);
    String paymentId = params.get("pg_payment_id");

    if (PAYOUT_CARD_RESULT_SCRIPT.equals(script)) {
      boolean explicitFailure =
          isFailureWord(params.get("pg_status")) || isFailureWord(params.get("pg_type"));
      String token = params.get("pg_card_token");
      return GatewayWebhookEvent.builder()
          .kind("PAYOUT_CARD")
          .resultStatus(!explicitFailure && notBlank(token) ? "SUCCESS" : "FAILED")
          .externalPaymentId(paymentId)
          .orderId(params.get("pg_order_id"))
          .userId(params.get("pg_user_id"))
          .cardToken(token)
          .cardPanMask(firstNonBlank(params.get("pg_card_hash"), params.get("pg_card_pan")))
          .providerStatusCode(firstNonBlank(params.get("pg_status"), params.get("pg_type")))
          .providerRequestId(requestId)
          .signature(params.get("pg_sig"))
          .rawParams(params)
          .build();
    }

    String resultStatus = mapWebhookResult(params);
    if ("REFUND".equals(params.get("pg_event_type")) || params.get("pg_refund_id") != null) {
      return GatewayWebhookEvent.builder()
          .kind("REFUND")
          .resultStatus(resultStatus)
          .externalPaymentId(params.get("pg_refund_id"))
          .providerRequestId(requestId)
          .signature(params.get("pg_sig"))
          .rawParams(params)
          .build();
    }
    if (PAYOUT_RESULT_SCRIPT.equals(script) || params.get("pg_payout_id") != null) {
      return GatewayWebhookEvent.builder()
          .kind("PAYOUT")
          .resultStatus(resultStatus)
          .externalPaymentId(firstNonBlank(params.get("pg_payout_id"), paymentId))
          .orderId(params.get("pg_order_id"))
          .amount(parseAmount(params.get("pg_payment_amount")))
          .providerStatusCode(params.get("pg_status"))
          .providerRequestId(requestId)
          .signature(params.get("pg_sig"))
          .rawParams(params)
          .build();
    }

    String capturedFlag = params.get("pg_captured");
    return GatewayWebhookEvent.builder()
        .kind("CHARGE")
        .resultStatus(resultStatus)
        .intentId(parseLongOrNull(params.get("pg_order_id")))
        .orderId(params.get("pg_order_id"))
        .externalPaymentId(paymentId)
        .amount(parseAmount(params.get("pg_amount")))
        .currency(params.getOrDefault("pg_currency", "KZT"))
        .captured(capturedFlag == null ? null : "1".equals(capturedFlag.trim()))
        .providerStatusCode(firstNonBlank(params.get("pg_payment_status"), params.get("pg_result")))
        .failureCode(params.get("pg_error_code"))
        .failureMessage(params.get("pg_error_description"))
        .cardPanMask(params.get("pg_card_pan"))
        .cardToken(
            firstNonBlank(
                params.get("pg_recurring_profile_id"), params.get("pg_recurring_profile")))
        .userId(params.get("pg_user_id"))
        .rawParams(params)
        .signature(params.get("pg_sig"))
        .providerRequestId(requestId)
        .build();
  }

  /**
   * Builds the signed XML reply FreedomPay expects on its callbacks (pg_salt + pg_sig are
   * mandatory). The reply is signed with the same script name and secret as the callback.
   */
  public String buildWebhookResponse(String script, String status, String description) {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("pg_status", status);
    p.put("pg_description", description);
    p.put("pg_salt", randomSalt());
    String sig = signatureService.sign(script, p, callbackSecret(script));
    return "<?xml version=\"1.0\" encoding=\"utf-8\"?><response>"
        + "<pg_status>"
        + xmlEscape(status)
        + "</pg_status>"
        + "<pg_description>"
        + xmlEscape(description)
        + "</pg_description>"
        + "<pg_salt>"
        + p.get("pg_salt")
        + "</pg_salt>"
        + "<pg_sig>"
        + sig
        + "</pg_sig></response>";
  }

  /**
   * Deduplication key for the durable inbox: identical re-deliveries (FreedomPay retries the result
   * URL every 30 minutes with a fresh pg_salt/pg_sig) collapse into one row, while a callback whose
   * business content differs gets its own row.
   */
  public String callbackRequestId(String script, FreedomPayMessage message) {
    List<FreedomPayMessage.Field> content = new ArrayList<>();
    for (FreedomPayMessage.Field f : message.fields()) {
      if (!"pg_salt".equals(f.name())
          && !FreedomPaySignatureService.SIGNATURE_FIELD.equals(f.name())) {
        content.add(f);
      }
    }
    String digest =
        FreedomPaySignatureService.md5Hex(
            script + "|" + canonical(content)); // MD5 is fine here: dedup key, not a security check
    String reference =
        firstNonBlank(
            message.get("pg_payment_id"),
            message.get("pg_payout_id"),
            message.get("pg_order_id"),
            "missing");
    return PROVIDER_NAME
        + ":"
        + properties.getMerchantId()
        + ":"
        + script
        + ":"
        + truncate(reference, 64)
        + ":"
        + digest;
  }

  private static String canonical(List<FreedomPayMessage.Field> fields) {
    StringBuilder sb = new StringBuilder();
    List<FreedomPayMessage.Field> sorted = new ArrayList<>(fields);
    sorted.sort(java.util.Comparator.comparing(FreedomPayMessage.Field::name));
    for (FreedomPayMessage.Field f : sorted) {
      sb.append(f.name()).append('=');
      if (f.isNested()) {
        sb.append('{').append(canonical(f.children())).append('}');
      } else {
        sb.append(f.value());
      }
      sb.append('\u0000');
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------- helpers

  private String callbackSecret(String script) {
    if (PAYOUT_RESULT_SCRIPT.equals(script)) {
      return payoutSecret();
    }
    if (PAYOUT_CARD_RESULT_SCRIPT.equals(script)) {
      return payoutCardSecret();
    }
    return merchantSecret();
  }

  private String merchantSecret() {
    return properties.getSecretKey();
  }

  private String payoutSecret() {
    return signatureService.payoutSecret();
  }

  private String payoutCardSecret() {
    return properties.isPayoutCardStorageUsesPayoutSecret() ? payoutSecret() : merchantSecret();
  }

  private int recurringLifetimeMonths() {
    return Math.max(1, Math.min(12, properties.getRecurringLifetimeMonths()));
  }

  /** The signed script name is the part of the URL after the last '/', up to '?'. */
  static String script(String path) {
    String p = path;
    int q = p.indexOf('?');
    if (q >= 0) p = p.substring(0, q);
    while (p.endsWith("/")) p = p.substring(0, p.length() - 1);
    int slash = p.lastIndexOf('/');
    return slash >= 0 ? p.substring(slash + 1) : p;
  }

  private Map<String, String> baseParams() {
    Map<String, String> p = new LinkedHashMap<>();
    p.put("pg_merchant_id", properties.getMerchantId());
    p.put("pg_salt", randomSalt());
    if ("1".equals(properties.getTestMode())) {
      p.put("pg_testing_mode", "1");
    }
    return p;
  }

  private static GatewayChargeResponse failedCharge(FreedomPayResponse response) {
    return GatewayChargeResponse.builder()
        .success(false)
        .providerStatusCode(response.status())
        .failureCode(response.get("pg_error_code"))
        .failureMessage(response.get("pg_error_description"))
        .build();
  }

  private static boolean isFailureWord(String value) {
    if (value == null) return false;
    String v = value.trim().toLowerCase(Locale.ROOT);
    return v.equals("error")
        || v.equals("failed")
        || v.equals("failure")
        || v.equals("declined")
        || v.equals("rejected");
  }

  private static BigDecimal sumNested(FreedomPayMessage message, String listName) {
    BigDecimal total = null;
    for (FreedomPayMessage.Field list : message.fields()) {
      if (!list.name().equals(listName) || !list.isNested()) continue;
      for (FreedomPayMessage.Field entry : list.children()) {
        List<FreedomPayMessage.Field> leaves = entry.isNested() ? entry.children() : List.of(entry);
        for (FreedomPayMessage.Field leaf : leaves) {
          if ("pg_amount".equals(leaf.name()) && !leaf.isNested()) {
            BigDecimal amount = parseAmount(leaf.value());
            if (amount != null) {
              total = (total == null ? BigDecimal.ZERO : total).add(amount.abs());
            }
          }
        }
      }
    }
    return total;
  }

  private static BigDecimal firstAmount(Object... values) {
    for (Object v : values) {
      if (v instanceof BigDecimal b) return b;
      if (v instanceof String s) {
        BigDecimal parsed = parseAmount(s);
        if (parsed != null) return parsed.abs();
      }
    }
    return null;
  }

  private static String randomSalt() {
    byte[] buf = new byte[8];
    RAND.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }

  private static String formatAmount(BigDecimal amount) {
    if (amount == null) return "0.00";
    return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static BigDecimal parseAmount(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return new BigDecimal(s.trim()).setScale(2, RoundingMode.HALF_UP);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static Long parseLongOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String nonNull(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }

  private static String xmlEscape(String value) {
    if (value == null) return "";
    return value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }

  private static String mapWebhookResult(Map<String, String> params) {
    String resultRaw = params.get("pg_result");
    if (resultRaw != null) {
      return switch (resultRaw.trim()) {
        case "1" -> "SUCCESS";
        case "0" -> "FAILED";
        default -> "PENDING";
      };
    }
    String paymentStatus =
        nonNull(params.get("pg_payment_status"), nonNull(params.get("pg_status"), ""))
            .trim()
            .toLowerCase(Locale.ROOT);
    return switch (paymentStatus) {
      case "success", "ok" -> "SUCCESS";
      case "error", "failed", "incomplete" -> "FAILED";
      default -> "PENDING";
    };
  }

  /** First non-blank value, or null if all are blank. */
  private static String firstNonBlank(String... values) {
    for (String v : values) {
      if (v != null && !v.isBlank()) return v;
    }
    return null;
  }

  private static String appendPaymentContext(String url, GatewayChargeRequest request) {
    if (url == null || url.isBlank() || request == null) {
      return url;
    }
    Map<String, String> context = new LinkedHashMap<>();
    putIfPresent(context, "intentId", request.getIntentId());
    putIfPresent(context, "roomMemberId", request.getRoomMemberId());
    putIfPresent(context, "roomId", request.getRoomId());
    if (context.isEmpty()) {
      return url;
    }
    StringBuilder out = new StringBuilder(url);
    out.append(url.contains("?") ? "&" : "?");
    boolean first = true;
    for (Map.Entry<String, String> entry : context.entrySet()) {
      if (!first) {
        out.append("&");
      }
      first = false;
      out.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
      out.append("=");
      out.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
    }
    return out.toString();
  }

  private static void putIfPresent(Map<String, String> target, String key, Long value) {
    if (value != null) {
      target.put(key, String.valueOf(value));
    }
  }
}
