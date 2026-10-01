package kz.hrms.splitupauth.payment.gateway.freedom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
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

  @Mock private FreedomPaySignatureService signatureService;
  @Mock private FreedomPayClient client;
  @Mock private FreedomPayUrlResolver urlResolver;

  private FreedomPayGateway gateway;

  @BeforeEach
  void setUp() {
    FreedomPayProperties properties = new FreedomPayProperties();
    properties.setMerchantId("merchant");
    properties.setTestMode("0");
    gateway = new FreedomPayGateway(properties, signatureService, client, urlResolver);
  }

  @Test
  void chargeSendsProviderIdempotencyKey() {
    when(signatureService.signWithMerchantSecret(eq("init_payment.php"), anyMap()))
        .thenReturn("sig");
    when(client.postForm(eq("/init_payment.php"), anyMap()))
        .thenReturn(Map.of("pg_status", "ok", "pg_payment_id", "pay-1"));

    gateway.initCharge(
        GatewayChargeRequest.builder()
            .intentId(1L)
            .idempotencyKey("charge-key")
            .amount(new BigDecimal("100.00"))
            .currency("KZT")
            .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client).postForm(eq("/init_payment.php"), params.capture());
    assertEquals("charge-key", params.getValue().get("pg_idempotency_key"));
  }

  @Test
  void refundSendsProviderIdempotencyKey() {
    when(signatureService.signWithMerchantSecret(eq("revoke.php"), anyMap())).thenReturn("sig");
    when(client.postForm(eq("/revoke.php"), anyMap())).thenReturn(Map.of("pg_status", "ok"));

    gateway.refund(
        GatewayRefundRequest.builder()
            .refundId(2L)
            .idempotencyKey("refund-key")
            .externalPaymentId("pay-1")
            .amount(new BigDecimal("50.00"))
            .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client).postForm(eq("/revoke.php"), params.capture());
    assertEquals("refund-key", params.getValue().get("pg_idempotency_key"));
  }

  @Test
  void payoutSendsProviderIdempotencyKey() {
    when(signatureService.signWithPayoutSecret(eq("reg2reg"), anyMap())).thenReturn("sig");
    when(client.postForm(eq("/api/reg2reg"), anyMap()))
        .thenReturn(Map.of("pg_status", "ok", "pg_payment_id", "provider-payout-3"));

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
    verify(client).postForm(eq("/api/reg2reg"), params.capture());
    assertEquals("payout-key", params.getValue().get("pg_idempotency_key"));
    assertEquals("card-token", params.getValue().get("pg_card_token_to"));
    assertEquals("user-7", params.getValue().get("pg_user_id"));
  }

  @Test
  void payoutCardBindingUsesUniversalAdd2AndMerchantSecretWithoutAmount() {
    when(urlResolver.cardStorageResultUrl()).thenReturn("https://api.test/card-storage-result");
    when(signatureService.signWithMerchantSecret(eq("add2"), anyMap())).thenReturn("sig");
    when(client.postForm(eq("/v1/merchant/merchant/cardstorage/add2"), anyMap()))
        .thenReturn(
            Map.of(
                "pg_status", "ok",
                "pg_payment_id", "binding-provider-1",
                "pg_redirect_url", "https://pay.test/binding"));

    var response =
        gateway.initCardBinding(
            GatewayCardBindingRequest.builder()
                .bindingId(17L)
                .userId("42")
                .backUrl("https://app.test/payment/card-connected?binding=17")
                .build());

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client).postForm(eq("/v1/merchant/merchant/cardstorage/add2"), params.capture());
    assertEquals("42", params.getValue().get("pg_user_id"));
    assertEquals("cardbind-17", params.getValue().get("pg_order_id"));
    assertEquals("https://api.test/card-storage-result", params.getValue().get("pg_post_link"));
    assertEquals(false, params.getValue().containsKey("pg_amount"));
    assertEquals(true, response.isSuccess());
    assertEquals("binding-provider-1", response.getExternalBindingId());
  }

  @Test
  void payoutCallbackUsesPaymentIdAndFinalPaymentStatus() {
    when(signatureService.verifyWithPayoutSecret(eq("payout-result"), anyMap())).thenReturn(true);

    GatewayWebhookEvent event =
        gateway.verifyAndParseWebhook(
            "payout-result",
            Map.of(
                "pg_payment_id", "provider-payout-3",
                "pg_order_id", "3",
                "pg_status", "ok",
                "pg_payment_status", "success",
                "pg_salt", "salt",
                "pg_sig", "sig"));

    assertEquals("PAYOUT", event.getKind());
    assertEquals("SUCCESS", event.getResultStatus());
    assertEquals("provider-payout-3", event.getExternalPaymentId());
  }

  @Test
  void payoutStatusUsesPayoutSecretAndDocumentedStatusEndpoint() {
    when(signatureService.signWithPayoutSecret(eq("payment_status2"), anyMap())).thenReturn("sig");
    when(client.postForm(eq("/api/payment_status2"), anyMap()))
        .thenReturn(
            Map.of(
                "pg_status", "ok",
                "pg_payment_status", "success",
                "pg_payment_id", "provider-payout-3"));

    GatewayStatusResponse response = gateway.getPayoutStatus("provider-payout-3", "3");

    ArgumentCaptor<Map<String, String>> params = ArgumentCaptor.forClass(Map.class);
    verify(client).postForm(eq("/api/payment_status2"), params.capture());
    assertEquals("provider-payout-3", params.getValue().get("pg_payment_id"));
    assertEquals("3", params.getValue().get("pg_order_id"));
    assertEquals("SUCCESS", response.getStatus());
  }
}
