package kz.hrms.splitupauth.payment.gateway.freedom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Fixtures follow the docs.freedompay.kz "Signature" rule: {@code script;values sorted by field
 * name;secret}, MD5 lowercase hex over UTF-8. The concatenated strings are the documented examples
 * with concrete merchant id / secret substituted for the placeholders.
 */
class FreedomPaySignatureServiceTest {

  private FreedomPaySignatureService service;

  @BeforeEach
  void setUp() {
    FreedomPayProperties properties = new FreedomPayProperties();
    properties.setSecretKey("test_secret_key");
    service = new FreedomPaySignatureService(properties);
  }

  @Test
  void initPaymentMatchesDocumentedConcatenation() {
    // docs: 'init_payment.php;25;Order
    // description;{{merchant_id}};23;some_random_string;{{secret}}'
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_order_id", "23");
    params.put("pg_merchant_id", "123456");
    params.put("pg_amount", "25");
    params.put("pg_description", "Order description");
    params.put("pg_salt", "some_random_string");

    assertEquals(
        FreedomPaySignatureService.md5Hex(
            "init_payment.php;25;Order description;123456;23;some_random_string;test_secret_key"),
        service.sign("init_payment.php", params, "test_secret_key"));
    assertEquals(
        "1d602ce1af2d04738e5840ff8562963f",
        service.sign("init_payment.php", params, "test_secret_key"));
  }

  @Test
  void cardStorageAddMatchesDocumentedConcatenation() {
    // docs: 'add;http://site.kz/back;{{merchant_id}};{{post_link}};some random
    // string;1234;{{secret}}'
    Map<String, String> params = new LinkedHashMap<>();
    params.put("pg_merchant_id", "123456");
    params.put("pg_user_id", "1234");
    params.put("pg_post_link", "http://site.kz/post");
    params.put("pg_back_link", "http://site.kz/back");
    params.put("pg_salt", "some random string");

    assertEquals(
        "337282103cc59ce0ecd3e21b8942ce06", service.sign("add", params, "test_secret_key"));
  }

  @Test
  void hashesUtf8ExplicitlyForCyrillicValues() {
    Map<String, String> params = Map.of("pg_description", "Оплата участия", "pg_salt", "s");
    assertEquals(
        FreedomPaySignatureService.md5Hex("init_payment.php;Оплата участия;s;test_secret_key"),
        service.sign("init_payment.php", params, "test_secret_key"));
  }

  @Test
  void repeatedFieldsKeepMessageOrderAndAreNotCollapsed() {
    FreedomPayMessage message =
        new FreedomPayMessage(
            List.of(
                FreedomPayMessage.Field.leaf("pg_b", "second-name"),
                FreedomPayMessage.Field.leaf("pg_a", "x2"),
                FreedomPayMessage.Field.leaf("pg_a", "x1"),
                FreedomPayMessage.Field.leaf("pg_salt", "s")));

    assertEquals(
        FreedomPaySignatureService.md5Hex("script;x2;x1;second-name;s;k"),
        service.sign("script", message, "k"));
    // Collapsing the duplicate (what a Map would do) must produce a different signature.
    assertNotEquals(
        service.sign("script", message, "k"),
        service.sign("script", Map.of("pg_a", "x1", "pg_b", "second-name", "pg_salt", "s"), "k"));
  }

  @Test
  void nestedXmlElementsAreSignedRecursivelyInPlace() {
    FreedomPayMessage message =
        FreedomPayXmlParser.parseMessage(
            "<response><pg_status>ok</pg_status>"
                + "<pg_refund_payments><pg_refund_payment><pg_payment_id>9</pg_payment_id>"
                + "<pg_amount>-10</pg_amount></pg_refund_payment></pg_refund_payments>"
                + "<pg_salt>s</pg_salt></response>");

    // pg_refund_payments sorts before pg_salt/pg_status; its children sorted by name: amount, id.
    assertEquals(
        FreedomPaySignatureService.md5Hex("get_status3.php;-10;9;s;ok;k"),
        service.sign("get_status3.php", message, "k"));
  }

  @Test
  void unknownExtraFieldsParticipateInTheSignature() {
    Map<String, String> base = new LinkedHashMap<>(Map.of("pg_status", "ok", "pg_salt", "s"));
    String withoutExtra = service.sign("result", base, "k");
    base.put("custom_param", "1");
    assertNotEquals(withoutExtra, service.sign("result", base, "k"));
  }

  @Test
  void verifyIsCaseInsensitiveAndRejectsMissingDuplicateOrWrongSignatures() {
    Map<String, String> params = new LinkedHashMap<>(Map.of("pg_status", "ok", "pg_salt", "s"));
    String sig = service.sign("result", params, "k");

    Map<String, String> good = new LinkedHashMap<>(params);
    good.put("pg_sig", sig.toUpperCase());
    assertTrue(service.verify("result", good, "k"));

    assertFalse(service.verify("result", params, "k"), "missing pg_sig");
    Map<String, String> tampered = new LinkedHashMap<>(good);
    tampered.put("pg_status", "error");
    assertFalse(service.verify("result", tampered, "k"), "tampered value");
    assertFalse(service.verify("other-script", good, "k"), "wrong script name");
    assertFalse(service.verify("result", good, "other-secret"), "wrong secret");
    assertFalse(service.verify("result", good, ""), "blank secret never verifies");

    FreedomPayMessage twoSigs =
        new FreedomPayMessage(
            List.of(
                FreedomPayMessage.Field.leaf("pg_status", "ok"),
                FreedomPayMessage.Field.leaf("pg_salt", "s"),
                FreedomPayMessage.Field.leaf("pg_sig", sig),
                FreedomPayMessage.Field.leaf("pg_sig", sig)));
    assertFalse(service.verify("result", twoSigs, "k"), "repeated pg_sig is ambiguous");
  }

  @Test
  void payoutSecretFallsBackToMerchantSecretOnlyWhenNotConfigured() {
    FreedomPayProperties properties = new FreedomPayProperties();
    properties.setSecretKey("merchant");
    FreedomPaySignatureService withoutPayoutSecret = new FreedomPaySignatureService(properties);
    assertEquals("merchant", withoutPayoutSecret.payoutSecret());
    properties.setPayoutSecretKey("payout");
    assertEquals("payout", withoutPayoutSecret.payoutSecret());
  }
}
