package kz.hrms.splitupauth.payment.gateway.freedom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayCardBindingResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeRequest;
import kz.hrms.splitupauth.payment.gateway.GatewayChargeResponse;
import kz.hrms.splitupauth.payment.gateway.GatewayStatusResponse;
import kz.hrms.splitupauth.payment.gateway.ProviderPaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

/**
 * Opt-in contract check against the FreedomPay SANDBOX ({@code test-api.freedompay.kz}). Skipped
 * unless {@code FREEDOMPAY_SANDBOX_IT=1}; credentials come only from {@code FREEDOMPAY_SANDBOX_*}
 * environment variables and are never printed. Only operations that move no money are called:
 * payment-page creation (never paid), status reads and payout-card tokenization sessions (never
 * completed). Calls are spaced as FreedomPay recommends.
 */
@EnabledIfEnvironmentVariable(named = "FREEDOMPAY_SANDBOX_IT", matches = "1")
class FreedomPaySandboxContractTest {

  private static final long SPACING_MS = 2_000;

  private FreedomPayProperties properties;
  private FreedomPayClient client;
  private FreedomPayGateway gateway;

  @BeforeEach
  void setUp() {
    String baseUrl = System.getenv("FREEDOMPAY_SANDBOX_BASE_URL");
    if (baseUrl == null || !baseUrl.startsWith("https://test-api.")) {
      throw new IllegalStateException("Sandbox contract test refuses any non-sandbox base URL");
    }
    properties = new FreedomPayProperties();
    properties.setBaseUrl(baseUrl);
    properties.setMerchantId(System.getenv("FREEDOMPAY_SANDBOX_MERCHANT_ID"));
    properties.setSecretKey(System.getenv("FREEDOMPAY_SANDBOX_SECRET_KEY"));
    properties.setPayoutSecretKey(System.getenv("FREEDOMPAY_SANDBOX_PAYOUT_SECRET_KEY"));
    properties.setTestMode("1");
    // Reserved example domain: callbacks are never delivered anywhere real.
    properties.setResultUrl("https://ecopay.example.com/api/v1/webhooks/freedompay/result");
    properties.setPayoutCardResultUrl(
        "https://ecopay.example.com/api/v1/webhooks/freedompay/payout-card-result");
    properties.setPayoutResultUrl(
        "https://ecopay.example.com/api/v1/webhooks/freedompay/payout-result");
    properties.setSuccessUrl("https://ecopay.example.com/payment/confirmation");
    properties.setFailureUrl("https://ecopay.example.com/payment/failure");
    FreedomPaySignatureService signatures = new FreedomPaySignatureService(properties);
    client =
        new FreedomPayClient(
            properties,
            signatures,
            new DefaultListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    gateway =
        new FreedomPayGateway(
            properties, signatures, client, new FreedomPayUrlResolver(properties));
  }

  @Test
  void initPaymentAndGetStatus3AreSignedAndUnpaidPageStaysNonFinal() throws Exception {
    String orderId = "sbx-" + UUID.randomUUID().toString().substring(0, 18);
    GatewayChargeResponse init =
        gateway.initCharge(
            GatewayChargeRequest.builder()
                .intentId(1L)
                .orderIdOverride(orderId)
                .idempotencyKey(UUID.randomUUID().toString())
                .amount(new BigDecimal("100"))
                .currency("KZT")
                .description("EcoPay sandbox contract check")
                .build());
    report("init_payment", init.isSuccess() + " status=" + init.getProviderStatusCode());
    assertTrue(init.isSuccess(), "signed ok with redirect URL: " + init.getFailureCode());
    assertNotNull(init.getExternalPaymentId());
    assertTrue(init.getPaymentUrl().startsWith("https://"));

    Thread.sleep(SPACING_MS);
    GatewayStatusResponse byId = gateway.getStatus(init.getExternalPaymentId());
    report("get_status3 by id", describe(byId));
    assertFalse(byId.isNotFound());
    assertNotFinalSuccess(byId);

    Thread.sleep(SPACING_MS);
    GatewayStatusResponse byOrder = gateway.getStatusByOrderId(orderId);
    report("get_status3 by order", describe(byOrder));
    assertNotFinalSuccess(byOrder);
    if (byOrder.getExternalPaymentId() != null) {
      assertEquals(init.getExternalPaymentId(), byOrder.getExternalPaymentId());
    }
  }

  @Test
  void unknownOrderIsReportedAsNotFound() throws Exception {
    Thread.sleep(SPACING_MS);
    GatewayStatusResponse status =
        gateway.getStatusByOrderId("sbx-missing-" + UUID.randomUUID().toString().substring(0, 8));
    report("get_status3 unknown order", describe(status));
    assertTrue(status.isNotFound() || "FAILED".equals(status.getStatus()));
  }

  @Test
  void unknownPayoutOrderIsNotFoundNotSuccess() throws Exception {
    Thread.sleep(SPACING_MS);
    GatewayStatusResponse status =
        gateway.getPayoutStatus(null, "sbx-po-" + UUID.randomUUID().toString().substring(0, 8));
    report("payment_status2 unknown order", describe(status));
    assertFalse("SUCCESS".equals(status.getStatus()));
  }

  /**
   * Answers the open contract question empirically: which endpoint, script name and secret the
   * sandbox accepts for payout-card tokenization. Prints raw provider answers (no secrets).
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "FREEDOMPAY_SANDBOX_PROBE", matches = "1")
  void probePayoutCardTokenizationVariants() throws Exception {
    String base = "/v1/merchant/" + properties.getMerchantId();
    String[][] variants = {
      {base + "/cardstoragepayout/add", "add", "payout"},
      {base + "/cardstoragepayout/add", "add", "merchant"},
      {base + "/cardstoragepayout/add", "add2", "payout"},
      {base + "/cardstoragepayout/add", "add2", "merchant"},
      {base + "/cardstoragepayout/add2", "add2", "payout"},
      {base + "/cardstoragepayout/add2", "add2", "merchant"},
      {base + "/cardstorage/add2", "add2", "merchant"},
    };
    for (String[] v : variants) {
      Thread.sleep(SPACING_MS);
      report(v[0].replace(base, "{m}") + " script=" + v[1] + " secret=" + v[2], rawProbe(v));
    }
  }

  private String rawProbe(String[] variant) throws Exception {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_merchant_id", properties.getMerchantId());
    params.put("pg_user_id", "sbx-" + UUID.randomUUID().toString().substring(0, 8));
    params.put("pg_post_link", properties.getPayoutCardResultUrl());
    params.put("pg_back_link", "https://ecopay.example.com/payment/card-connected");
    params.put("pg_salt", UUID.randomUUID().toString().replace("-", ""));
    String secret =
        "payout".equals(variant[2]) ? properties.getPayoutSecretKey() : properties.getSecretKey();
    params.put(
        "pg_sig", new FreedomPaySignatureService(properties).sign(variant[1], params, secret));
    StringBuilder form = new StringBuilder();
    params.forEach(
        (k, val) ->
            form.append(form.isEmpty() ? "" : "&")
                .append(k)
                .append('=')
                .append(java.net.URLEncoder.encode(val, java.nio.charset.StandardCharsets.UTF_8)));
    java.net.http.HttpResponse<String> res =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(
                        java.net.URI.create(properties.getBaseUrl() + variant[0]))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(form.toString()))
                    .build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
    String body =
        res.body()
            .replaceAll("\\s+", " ")
            .replaceAll("<pg_sig>[^<]*</pg_sig>", "<pg_sig>…</pg_sig>");
    body = body.replaceAll("https://[^<\"]*", "<url>");
    return "http=" + res.statusCode() + " body=" + body.substring(0, Math.min(400, body.length()));
  }

  @Test
  void payoutCardTokenizationSessionStarts() throws Exception {
    Thread.sleep(SPACING_MS);
    GatewayCardBindingResponse binding =
        gateway.initCardBinding(
            GatewayCardBindingRequest.builder()
                .bindingId(1L)
                .userId("sbx-" + UUID.randomUUID().toString().substring(0, 8))
                .backUrl("https://ecopay.example.com/payment/card-connected")
                .build());
    report(
        "cardstoragepayout/add via gateway",
        binding.isSuccess() + " status=" + binding.getProviderStatusCode());
    assertTrue(binding.isSuccess(), "default payout-card tokenization must start");
    assertNotNull(binding.getExternalBindingId());
  }

  private static void assertNotFinalSuccess(GatewayStatusResponse status) {
    assertFalse("SUCCESS".equals(status.getStatus()), "an unpaid page must never be SUCCESS");
    assertFalse(status.getProviderState() == ProviderPaymentState.CAPTURED);
  }

  private static String describe(GatewayStatusResponse s) {
    return "status="
        + s.getStatus()
        + " state="
        + s.getProviderState()
        + " provider="
        + s.getProviderStatusCode()
        + " notFound="
        + s.isNotFound()
        + " amount="
        + s.getAmount()
        + " currency="
        + s.getCurrency()
        + " captured="
        + s.getCaptured()
        + " failure="
        + s.getFailureCode();
  }

  private static void report(String step, String outcome) {
    System.out.println("[sandbox] " + step + " -> " + outcome);
  }
}
