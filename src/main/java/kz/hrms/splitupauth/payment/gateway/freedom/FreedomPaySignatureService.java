package kz.hrms.splitupauth.payment.gateway.freedom;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * Freedom Pay (PayBox) signature service.
 *
 * Signature algorithm:
 *   1. Take all params except `pg_sig`
 *   2. Sort by key (alphabetical)
 *   3. Concatenate values with `;` between, prefixed by script name and suffixed by secret key
 *   4. MD5 the result and lowercase hex
 *
 * See: https://docs.freedompay.kz/
 */
@Component
@RequiredArgsConstructor
public class FreedomPaySignatureService {

    private final FreedomPayProperties properties;

    public String sign(String script, Map<String, String> params, String secretKey) {
        TreeMap<String, String> sorted = new TreeMap<>(params);
        sorted.remove("pg_sig");

        StringBuilder sb = new StringBuilder();
        sb.append(script);
        for (String value : sorted.values()) {
            sb.append(';').append(value == null ? "" : value);
        }
        sb.append(';').append(secretKey);

        return md5Hex(sb.toString());
    }

    public String signWithMerchantSecret(String script, Map<String, String> params) {
        return sign(script, params, properties.getSecretKey());
    }

    public String signWithPayoutSecret(String script, Map<String, String> params) {
        String key = properties.getPayoutSecretKey();
        if (key == null || key.isBlank()) key = properties.getSecretKey();
        return sign(script, params, key);
    }

    public boolean verify(String script, Map<String, String> params, String secretKey) {
        String signature = params.get("pg_sig");
        if (signature == null) return false;
        String expected = sign(script, params, secretKey);
        return expected.equalsIgnoreCase(signature);
    }

    public boolean verifyWithMerchantSecret(String script, Map<String, String> params) {
        return verify(script, params, properties.getSecretKey());
    }

    public boolean verifyWithPayoutSecret(String script, Map<String, String> params) {
        String key = properties.getPayoutSecretKey();
        if (key == null || key.isBlank()) key = properties.getSecretKey();
        return verify(script, params, key);
    }

    private static String md5Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }
}
