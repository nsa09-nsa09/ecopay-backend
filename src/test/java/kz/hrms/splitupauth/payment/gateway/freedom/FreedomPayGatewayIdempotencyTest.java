package kz.hrms.splitupauth.payment.gateway.freedom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayPayoutRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayRefundRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FreedomPayGatewayIdempotencyTest {

  @Mock private FreedomPayClient client;
  @Mock private FreedomPayUrlResolver urlResolver;

  private FreedomPayProperties properties;
  private FreedomPaySignatureService signatureService;
  private FreedomPayGateway gateway;

  @BeforeEach
  void setUp() {
    properties = new FreedomPayProperties();
    properties.setMerchantId("merchant");
    properties.setSecretKey("merchant-secret");
    properties.setPayoutSecretKey("payout-secret");
    properties.setTestMode("0");
    signatureService = new FreedomPaySignatureService(properties);
    gateway = new FreedomPayGateway(properties, signatureService, client, urlResolver);
  }

  static FreedomPayResponse signed(Map<String, String> fields) {
    return new FreedomPayResponse(FreedomPayMessage.of(fields), fields, true);
  }

  @Test
  void chargeSendsProviderIdempotencyKeyAndRequestsOneStepClearing() {
    when(client.call(
            eq("init_payment"),
            eq("/init_payment.php"),
            eq("init_payment.php"),
            anyMap(),
            eq("merchant-secret"),
            eq(false)))
        .thenReturn(
            signed(
                Map.of(
                    "pg_status", "ok",
                    "pg_payment_id", "pay-1",
                    "pg_redirect_url", "https://pay.test/1")));

    var response =
        gateway.initCharge(
            GatewayChargeRequest.builder()
                .intentId(1L)
                .idempotencyKey("charge-key")
                .amount(new BigDecimal("100.00"))
                .currency("KZT")
                .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .call(
            eq("init_payment"),
            eq("/init_payment.php"),
            eq("init_payment.php"),
            params.capture(),
            eq("merchant-secret"),
            eq(false));
    assertEquals("charge-key", params.getValue().get("pg_idempotency_key"));
    assertEquals("1", params.getValue().get("pg_auto_clearing"));
    assertEquals("1800", params.getValue().get("pg_lifetime"));
    assertEquals("1", params.getValue().get("pg_order_id"));
    assertTrue(response.isSuccess());
    assertTrue(response.isRequiresRedirect());
    assertFalse(response.isCaptureConfirmed(), "a redirect URL is never proof of capture");
  }

  @Test
  void refundOfCapturedPaymentUsesCurrentRevokeEndpointWithIdempotencyKey() {
    when(client.call(
            eq("status"),
            eq("/get_status3.php"),
            eq("get_status3.php"),
            anyMap(),
            anyString(),
            eq(true)))
        .thenReturn(
            signed(
                Map.of(
                    "pg_status", "ok",
                    "pg_payment_id", "pay-1",
                    "pg_payment_status", "success",
                    "pg_captured", "1",
                    "pg_amount", "100.00")));
    when(client.call(
            eq("refund"), eq("/revoke"), eq("revoke"), anyMap(), eq("merchant-secret"), eq(false)))
        .thenReturn(signed(Map.of("pg_status", "ok")));

    var response =
        gateway.refund(
            GatewayRefundRequest.builder()
                .refundId(2L)
                .idempotencyKey("refund-key")
                .externalPaymentId("pay-1")
                .amount(new BigDecimal("50.00"))
                .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .call(
            eq("refund"),
            eq("/revoke"),
            eq("revoke"),
            params.capture(),
            eq("merchant-secret"),
            eq(false));
    assertEquals("refund-key", params.getValue().get("pg_idempotency_key"));
    assertEquals("50.00", params.getValue().get("pg_refund_amount"));
    assertTrue(response.isPending(), "revoke ok is acceptance, settled later by status");
  }

  @Test
  void payoutSendsProviderIdempotencyKeyWithPayoutSecret() {
    when(client.call(
            eq("payout"),
            eq("/api/reg2reg"),
            eq("reg2reg"),
            anyMap(),
            eq("payout-secret"),
            eq(false)))
        .thenReturn(signed(Map.of("pg_status", "ok", "pg_payment_id", "provider-payout-3")));

    gateway.payout(
        GatewayPayoutRequest.builder()
            .payoutId(3L)
            .idempotencyKey("payout-key")
            .destinationUserId("user-7")
            .destinationCardToken("card-token")
            .amount(new BigDecimal("40.00"))
            .currency("KZT")
            .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .call(
            eq("payout"),
            eq("/api/reg2reg"),
            eq("reg2reg"),
            params.capture(),
            eq("payout-secret"),
            eq(false));
    assertEquals("payout-key", params.getValue().get("pg_idempotency_key"));
    assertEquals("card-token", params.getValue().get("pg_card_token_to"));
    assertEquals("user-7", params.getValue().get("pg_user_id"));
  }

  @Test
  void payoutCardBindingUsesPayoutCardStorageNotPurchaseAdd2() {
    when(urlResolver.payoutCardResultUrl()).thenReturn("https://api.test/payout-card-result");
    when(client.call(
            eq("payout_card_add"),
            eq("/v1/merchant/merchant/cardstoragepayout/add"),
            eq("add"),
            anyMap(),
            eq("merchant-secret"),
            eq(false)))
        .thenReturn(
            signed(
                Map.of(
                    "pg_status", "ok",
                    "pg_payment_id", "binding-provider-1",
                    "pg_redirect_url", "https://pay.test/cardstoragepayout/view")));

    var response =
        gateway.initCardBinding(
            GatewayCardBindingRequest.builder()
                .bindingId(17L)
                .userId("42")
                .backUrl("https://app.test/payment/card-connected?binding=17")
                .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .call(
            eq("payout_card_add"),
            eq("/v1/merchant/merchant/cardstoragepayout/add"),
            eq("add"),
            params.capture(),
            eq("merchant-secret"),
            eq(false));
    assertEquals("42", params.getValue().get("pg_user_id"));
    assertEquals("https://api.test/payout-card-result", params.getValue().get("pg_post_link"));
    assertFalse(params.getValue().containsKey("pg_amount"));
    assertTrue(response.isSuccess());
    assertEquals("binding-provider-1", response.getExternalBindingId());
  }

  @Test
  void payoutCallbackUsesPaymentIdOrderIdAndAmount() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_payment_id", "provider-payout-3");
    params.put("pg_order_id", "ecopay-payout-3");
    params.put("pg_status", "ok");
    params.put("pg_payment_amount", "40.00");
    params.put("pg_salt", "salt");
    params.put("pg_sig", signatureService.sign("payout-result", params, "payout-secret"));

    assertTrue(gateway.verifyWebhookSignature("payout-result", params));
    GatewayWebhookEvent event = gateway.verifyAndParseWebhook("payout-result", params);

    assertEquals("PAYOUT", event.getKind());
    assertEquals("SUCCESS", event.getResultStatus());
    assertEquals("provider-payout-3", event.getExternalPaymentId());
    assertEquals("ecopay-payout-3", event.getOrderId());
    assertEquals(new BigDecimal("40.00"), event.getAmount());
  }

  @Test
  void payoutCallbackSignedWithPurchaseSecretIsRejected() {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_payment_id", "provider-payout-3");
    params.put("pg_status", "ok");
    params.put("pg_salt", "salt");
    params.put("pg_sig", signatureService.sign("payout-result", params, "merchant-secret"));

    assertFalse(gateway.verifyWebhookSignature("payout-result", params));
  }

  @Test
  void payoutStatusUsesPayoutSecretAndDocumentedStatusEndpoint() {
    when(client.call(
            eq("payout_status"),
            eq("/api/payment_status2"),
            eq("payment_status2"),
            anyMap(),
            eq("payout-secret"),
            eq(true)))
        .thenReturn(
            signed(
                Map.of(
                    "pg_status", "ok",
                    "pg_payment_status", "success",
                    "pg_payment_id", "provider-payout-3")));

    GatewayStatusResponse response = gateway.getPayoutStatus("provider-payout-3", "3");

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client)
        .call(
            eq("payout_status"),
            eq("/api/payment_status2"),
            eq("payment_status2"),
            params.capture(),
            eq("payout-secret"),
            eq(true));
    assertEquals("provider-payout-3", params.getValue().get("pg_payment_id"));
    assertEquals("3", params.getValue().get("pg_order_id"));
    assertEquals("SUCCESS", response.getStatus());
  }
}
