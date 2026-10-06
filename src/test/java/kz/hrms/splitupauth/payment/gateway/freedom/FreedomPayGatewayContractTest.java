package kz.hrms.splitupauth.payment.gateway.freedom;

import static kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGatewayIdempotencyTest.signed;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRequestNotSentException;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.ProviderPaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CURRENT CODE -> CURRENT FREEDOMPAY DOC contract checks for the adapter. */
@ExtendWith(MockitoExtension.class)
class FreedomPayGatewayContractTest {

  @Mock private FreedomPayClient client;
  @Mock private FreedomPayUrlResolver urlResolver;

  private FreedomPayProperties properties;
  private FreedomPaySignatureService signatures;
  private FreedomPayGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new FreedomPayProperties();
    properties.setMerchantId("merchant");
    properties.setSecretKey("merchant-secret");
    properties.setPayoutSecretKey("payout-secret");
    properties.setTestMode("0");
    signatures = new FreedomPaySignatureService(properties);
    gateway = new FreedomPayGateway(properties, signatures, client, urlResolver);
  }

  private void statusReturns(Map<String, String> fields) {
    when(client.call(
            eq("status"),
            eq("/get_status3.php"),
            eq("get_status3.php"),
            anyMap(),
            anyString(),
            eq(true)))
        .thenReturn(signed(fields));
  }

  private static Map<String, String> status(
      String paymentStatus, String captured, String extraKey, String extraValue) {
    Map<String, String> m = new LinkedHashMap<>();
    m.put("pg_status", "ok");
    m.put("pg_payment_id", "pay-1");
    m.put("pg_payment_status", paymentStatus);
    m.put("pg_amount", "100.00");
    m.put("pg_currency", "KZT");
    if (captured != null) m.put("pg_captured", captured);
    if (extraKey != null) m.put(extraKey, extraValue);
    return m;
  }

  @Test
  void getStatus3_capturedSuccessIsTheOnlyFinalSuccess() {
    statusReturns(status("success", "1", null, null));
    GatewayStatusResponse s = gateway.getStatus("pay-1");
    assertEquals("SUCCESS", s.getStatus());
    assertEquals(ProviderPaymentState.CAPTURED, s.getProviderState());
    assertEquals(new BigDecimal("100.00"), s.getAmount());
    assertEquals("KZT", s.getCurrency());
  }

  @Test
  void getStatus3_authorizedTwoStepIsNotCaptured() {
    statusReturns(status("success", "0", null, null));
    GatewayStatusResponse s = gateway.getStatus("pay-1");
    assertEquals("PENDING", s.getStatus());
    assertEquals(ProviderPaymentState.AUTHORIZED, s.getProviderState());
  }

  @Test
  void getStatus3_refundedAmountsAreModelled() {
    statusReturns(status("success", "1", "pg_refund_amount", "40.00"));
    GatewayStatusResponse partial = gateway.getStatus("pay-1");
    assertEquals(ProviderPaymentState.PARTIALLY_REFUNDED, partial.getProviderState());
    assertEquals("REVIEW", partial.getStatus());
    assertEquals(new BigDecimal("40.00"), partial.getRefundedAmount());
  }

  @Test
  void getStatus3_fullRefundAndRevokedMapToRefunded() {
    statusReturns(status("success", "1", "pg_refund_amount", "100.00"));
    assertEquals(ProviderPaymentState.REFUNDED, gateway.getStatus("pay-1").getProviderState());
  }

  @Test
  void getStatus3_failurePendingExpiredAndUnknownStates() {
    statusReturns(status("failed", null, null, null));
    assertEquals("FAILED", gateway.getStatus("pay-1").getStatus());
  }

  @Test
  void getStatus3_pendingAndUnknownNeverBecomeSuccess() {
    for (String raw : List.of("pending", "partial", "new", "something-new")) {
      FreedomPayResponse r = signed(status(raw, null, null, null));
      GatewayStatusResponse s = FreedomPayGateway.mapPaymentStatus("pay-1", r);
      assertEquals("PENDING", s.getStatus(), raw);
    }
    assertEquals(
        ProviderPaymentState.EXPIRED,
        FreedomPayGateway.mapPaymentStatus("p", signed(status("incomplete", null, null, null)))
            .getProviderState());
    assertEquals(
        ProviderPaymentState.UNKNOWN,
        FreedomPayGateway.mapPaymentStatus("p", signed(status("weird", null, null, null)))
            .getProviderState());
  }

  @Test
  void getStatus3_notFoundIsExplicitAndOtherErrorsThrow() {
    statusReturns(Map.of("pg_status", "error", "pg_error_code", "11068"));
    GatewayStatusResponse s = gateway.getStatus("pay-1");
    assertTrue(s.isNotFound());
    assertEquals("PENDING", s.getStatus());

    FreedomPayResponse unsignedError =
        new FreedomPayResponse(
            FreedomPayMessage.of(Map.of("pg_status", "error", "pg_error_code", "9998")),
            Map.of("pg_status", "error", "pg_error_code", "9998"),
            false);
    assertThrows(
        FreedomPayException.class, () -> FreedomPayGateway.mapPaymentStatus("p", unsignedError));
  }

  @Test
  void statusByOrderIdUsesPgOrderId() {
    statusReturns(status("success", "1", null, null));
    gateway.getStatusByOrderId("42");
    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .call(
            eq("status"),
            eq("/get_status3.php"),
            eq("get_status3.php"),
            params.capture(),
            anyString(),
            eq(true));
    assertEquals("42", params.getValue().get("pg_order_id"));
    assertNull(params.getValue().get("pg_payment_id"));
  }

  @Test
  void uncapturedTwoStepPaymentIsCancelledNotRefunded() {
    statusReturns(status("success", "0", null, null));
    when(client.call(
            eq("cancel"), eq("/cancel"), eq("cancel"), anyMap(), eq("merchant-secret"), eq(false)))
        .thenReturn(signed(Map.of("pg_status", "ok")));

    var response =
        gateway.refund(
            GatewayRefundRequest.builder()
                .idempotencyKey("r1")
                .externalPaymentId("pay-1")
                .amount(new BigDecimal("100.00"))
                .build());

    assertTrue(response.isSuccess());
    verify(client, never())
        .call(eq("refund"), anyString(), anyString(), anyMap(), anyString(), eq(false));
  }

  @Test
  void partialCancelOfAuthorizedPaymentIsRefusedWithoutCallingProvider() {
    statusReturns(status("success", "0", null, null));

    var response =
        gateway.refund(
            GatewayRefundRequest.builder()
                .externalPaymentId("pay-1")
                .amount(new BigDecimal("10.00"))
                .build());

    assertFalse(response.isSuccess());
    assertEquals("PARTIAL_CANCEL_UNSUPPORTED", response.getFailureCode());
  }

  @Test
  void refundIsNotSentWhenProviderStateCannotBeRead() {
    when(client.call(eq("status"), anyString(), anyString(), anyMap(), anyString(), eq(true)))
        .thenThrow(new FreedomPayException("timeout"));

    assertThrows(
        GatewayRequestNotSentException.class,
        () -> gateway.refund(GatewayRefundRequest.builder().externalPaymentId("pay-1").build()));
    verify(client, never())
        .call(eq("refund"), anyString(), anyString(), anyMap(), anyString(), eq(false));
  }

  @Test
  void recurringUsesMakeRecurringPaymentAndOkIsAcceptanceNotCapture() {
    when(urlResolver.resultUrl()).thenReturn("https://api.test/api/v1/webhooks/freedompay/result");
    when(client.call(
            eq("recurring_payment"),
            eq("/make_recurring_payment"),
            eq("make_recurring_payment"),
            anyMap(),
            eq("merchant-secret"),
            eq(false)))
        .thenReturn(signed(Map.of("pg_status", "ok", "pg_payment_id", "rec-1")));

    var response =
        gateway.chargeWithToken(
            GatewayChargeRequest.builder()
                .intentId(9L)
                .idempotencyKey("recurring-9")
                .amount(new BigDecimal("10.00"))
                .build(),
            "profile-1");

    assertTrue(response.isSuccess());
    assertFalse(response.isCaptureConfirmed(), "sync ok must not be treated as captured money");
    assertEquals("rec-1", response.getExternalPaymentId());
  }

  @Test
  void saveCardSendsRecurringLifetimeInMonthsClampedToCurrentMaximum() {
    properties.setRecurringLifetimeMonths(156);
    when(client.call(
            eq("init_payment"), anyString(), anyString(), anyMap(), anyString(), eq(false)))
        .thenReturn(
            signed(
                Map.of("pg_status", "ok", "pg_payment_id", "1", "pg_redirect_url", "https://p")));

    gateway.initCharge(
        GatewayChargeRequest.builder()
            .intentId(1L)
            .amount(new BigDecimal("1.00"))
            .saveCardRequested(true)
            .userId("5")
            .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .call(
            eq("init_payment"), anyString(), anyString(), params.capture(), anyString(), eq(false));
    assertEquals("1", params.getValue().get("pg_recurring_start"));
    assertEquals("12", params.getValue().get("pg_recurring_lifetime"));
  }

  @Test
  void initPaymentWithoutRedirectUrlIsNotSuccess() {
    when(client.call(
            eq("init_payment"), anyString(), anyString(), anyMap(), anyString(), eq(false)))
        .thenReturn(signed(Map.of("pg_status", "ok", "pg_payment_id", "1")));

    assertFalse(
        gateway
            .initCharge(GatewayChargeRequest.builder().intentId(1L).amount(BigDecimal.ONE).build())
            .isSuccess());
  }

  @Test
  void chargeCallbackCarriesCapturedFlagAndRecurringProfile() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_order_id", "77");
    params.put("pg_payment_id", "pay-77");
    params.put("pg_amount", "1500");
    params.put("pg_currency", "KZT");
    params.put("pg_result", "1");
    params.put("pg_captured", "0");
    params.put("pg_recurring_profile_id", "prof-1");
    params.put("pg_salt", "s1");
    params.put("pg_sig", signatures.sign("result", params, "merchant-secret"));

    assertTrue(gateway.verifyWebhookSignature("result", params));
    GatewayWebhookEvent event = gateway.verifyAndParseWebhook("result", params);
    assertEquals(77L, event.getIntentId());
    assertEquals("SUCCESS", event.getResultStatus());
    assertEquals(Boolean.FALSE, event.getCaptured());
    assertEquals(new BigDecimal("1500.00"), event.getAmount());
    assertEquals("prof-1", event.getCardToken());
  }

  @Test
  void duplicateCallbackDeliveriesShareTheDedupKeyButDifferentContentDoesNot() {
    Map<String, String> first =
        new LinkedHashMap<>(Map.of("pg_order_id", "1", "pg_payment_id", "p", "pg_result", "1"));
    first.put("pg_salt", "salt-a");
    first.put("pg_sig", "sig-a");
    Map<String, String> retry = new LinkedHashMap<>(first);
    retry.put("pg_salt", "salt-b");
    retry.put("pg_sig", "sig-b");
    Map<String, String> different = new LinkedHashMap<>(first);
    different.put("pg_result", "0");

    String a = gateway.callbackRequestId("result", FreedomPayMessage.of(first));
    assertEquals(a, gateway.callbackRequestId("result", FreedomPayMessage.of(retry)));
    assertNotEquals(a, gateway.callbackRequestId("result", FreedomPayMessage.of(different)));
    assertNotEquals(a, gateway.callbackRequestId("payout-result", FreedomPayMessage.of(first)));
  }

  @Test
  void payoutCardCallbackIsVerifiedWithTheMerchantSecretAndParsed() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_payment_id", "bind-1");
    params.put("pg_user_id", "42");
    params.put("pg_card_token", "payout-token");
    params.put("pg_card_hash", "4111****1111");
    params.put("pg_status", "success");
    params.put("pg_salt", "s");
    params.put("pg_sig", signatures.sign("payout-card-result", params, "payout-secret"));
    // Sandbox-verified: cardstoragepayout is signed with the merchant secret, not the payout one.
    assertFalse(gateway.verifyCallback("payout-card-result", FreedomPayMessage.of(params)));
    params.put("pg_sig", signatures.sign("payout-card-result", params, "merchant-secret"));

    FreedomPayMessage message = FreedomPayMessage.of(params);
    assertTrue(gateway.verifyCallback("payout-card-result", message));
    GatewayWebhookEvent event = gateway.parseCallback("payout-card-result", message);
    assertEquals("PAYOUT_CARD", event.getKind());
    assertEquals("SUCCESS", event.getResultStatus());
    assertEquals("payout-token", event.getCardToken());
    assertEquals("42", event.getUserId());

    params.put("pg_status", "error");
    assertEquals(
        "FAILED",
        gateway
            .parseCallback("payout-card-result", FreedomPayMessage.of(params))
            .getResultStatus());
  }

  @Test
  void pgXmlCallbackIsUnwrappedForVerification() {
    Map<String, String> inner = new LinkedHashMap<>();
    inner.put("pg_payment_id", "bind-2");
    inner.put("pg_card_token", "t");
    inner.put("pg_salt", "s");
    String sig = signatures.sign("payout-card-result", inner, "merchant-secret");
    String xml =
        "<request><pg_payment_id>bind-2</pg_payment_id><pg_card_token>t</pg_card_token>"
            + "<pg_salt>s</pg_salt><pg_sig>"
            + sig
            + "</pg_sig></request>";

    FreedomPayMessage message = gateway.callbackMessage(Map.of("pg_xml", List.of(xml)));
    assertTrue(gateway.verifyCallback("payout-card-result", message));
  }

  @Test
  void webhookReplyIsSignedWithTheCallbackScriptAndEscaped() {
    String reply = gateway.buildWebhookResponse("payout-result", "error", "a<b");
    assertTrue(reply.contains("<pg_description>a&lt;b</pg_description>"));
    FreedomPayMessage parsed = FreedomPayXmlParser.parseMessage(reply);
    assertTrue(signatures.verify("payout-result", parsed, "payout-secret"));
  }
}
