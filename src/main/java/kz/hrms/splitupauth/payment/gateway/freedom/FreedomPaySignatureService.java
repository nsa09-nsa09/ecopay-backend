package kz.hrms.splitupauth.payment.gateway.freedom;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * FreedomPay (PayBox) {@code pg_sig} computation and verification.
 *
 * <p>Rule (docs.freedompay.kz "Overview → Signature", identical in the legacy merchant-api intro):
 *
 * <ol>
 *   <li>the name of the script being called — the URL part after the last {@code /} up to the end
 *       or {@code ?};
 *   <li>the values of ALL message fields (including {@code pg_salt} and any extra/unknown field,
 *       excluding {@code pg_sig}) ordered alphabetically by field name; fields with the same name
 *       keep their message order; the rule is applied recursively to nested XML tags;
 *   <li>the secret key;
 * </ol>
 *
 * joined with {@code ;}, MD5-hashed over UTF-8 bytes, lowercase hex.
 *
 * <p>Requests are signed with the payment ("receiving") secret; payout operations with the payout
 * secret when one is configured.
 */
@Component
@RequiredArgsConstructor
public class FreedomPaySignatureService {

  public static final String SIGNATURE_FIELD = "pg_sig";

  private static final Comparator<FreedomPayMessage.Field> BY_NAME =
      Comparator.comparing(FreedomPayMessage.Field::name);

  private final FreedomPayProperties properties;

  public String sign(String script, Map<String, String> params, String secretKey) {
    return sign(script, FreedomPayMessage.of(params), secretKey);
  }

  public String sign(String script, FreedomPayMessage message, String secretKey) {
    List<String> parts = new ArrayList<>();
    parts.add(script == null ? "" : script);
    List<FreedomPayMessage.Field> topLevel = new ArrayList<>();
    for (FreedomPayMessage.Field f : message.fields()) {
      if (!SIGNATURE_FIELD.equals(f.name())) {
        topLevel.add(f);
      }
    }
    appendSortedValues(topLevel, parts);
    parts.add(secretKey == null ? "" : secretKey);
    return md5Hex(String.join(";", parts));
  }

  /**
   * Stable sort keeps same-name fields in message order; nested elements contribute their own
   * sorted leaf values in place.
   */
  private static void appendSortedValues(List<FreedomPayMessage.Field> fields, List<String> out) {
    List<FreedomPayMessage.Field> sorted = new ArrayList<>(fields);
    sorted.sort(BY_NAME);
    for (FreedomPayMessage.Field f : sorted) {
      if (f.isNested()) {
        appendSortedValues(f.children(), out);
      } else {
        out.add(f.value());
      }
    }
  }

  public String signWithMerchantSecret(String script, Map<String, String> params) {
    return sign(script, params, properties.getSecretKey());
  }

  public String signWithPayoutSecret(String script, Map<String, String> params) {
    return sign(script, params, payoutSecret());
  }

  public boolean verify(String script, Map<String, String> params, String secretKey) {
    return verify(script, FreedomPayMessage.of(params), secretKey);
  }

  /** Constant-time comparison; a missing, blank or repeated {@code pg_sig} never verifies. */
  public boolean verify(String script, FreedomPayMessage message, String secretKey) {
    if (message.count(SIGNATURE_FIELD) != 1) return false;
    String signature = message.get(SIGNATURE_FIELD);
    if (signature == null || signature.isBlank() || secretKey == null || secretKey.isBlank()) {
      return false;
    }
    String expected = sign(script, message, secretKey);
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.US_ASCII),
        signature.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII));
  }

  public boolean verifyWithMerchantSecret(String script, Map<String, String> params) {
    return verify(script, params, properties.getSecretKey());
  }

  public boolean verifyWithPayoutSecret(String script, Map<String, String> params) {
    return verify(script, params, payoutSecret());
  }

  public boolean verifyWithMerchantSecret(String script, FreedomPayMessage message) {
    return verify(script, message, properties.getSecretKey());
  }

  public boolean verifyWithPayoutSecret(String script, FreedomPayMessage message) {
    return verify(script, message, payoutSecret());
  }

  String payoutSecret() {
    String key = properties.getPayoutSecretKey();
    return key == null || key.isBlank() ? properties.getSecretKey() : key;
  }

  static String md5Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("MD5");
      return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("MD5 not available", e);
    }
  }
}
