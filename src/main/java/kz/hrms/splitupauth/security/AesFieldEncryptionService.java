package kz.hrms.splitupauth.security;

import jakarta.annotation.PostConstruct;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AesFieldEncryptionService implements FieldEncryptionService {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final String CURRENT_PREFIX = "v1:gcm:";
  private static final int TAG_LENGTH_BIT = 128;
  private static final int IV_LENGTH_BYTE = 12;

  @Value("${app.security.field-encryption-key}")
  private String base64Key;

  private SecretKey secretKey;

  @PostConstruct
  public void init() {
    if (base64Key == null
        || base64Key.isBlank()
        || "replace-with-32-byte-base64-key".equals(base64Key.trim())) {
      throw new IllegalStateException("Field encryption key must be configured explicitly");
    }
    byte[] decodedKey = Base64.getDecoder().decode(base64Key);
    if (decodedKey.length != 32) {
      throw new IllegalStateException("Field encryption key must be 32 bytes (Base64 encoded)");
    }
    this.secretKey = new SecretKeySpec(decodedKey, "AES");
  }

  @Override
  public String encrypt(String rawValue) {
    try {
      byte[] iv = new byte[IV_LENGTH_BYTE];
      new SecureRandom().nextBytes(iv);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

      byte[] cipherText = cipher.doFinal(rawValue.getBytes(StandardCharsets.UTF_8));

      ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);
      byteBuffer.put(iv);
      byteBuffer.put(cipherText);

      return CURRENT_PREFIX + Base64.getEncoder().encodeToString(byteBuffer.array());
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to encrypt field", ex);
    }
  }

  @Override
  public String decrypt(String encryptedValue) {
    try {
      String payload =
          encryptedValue != null && encryptedValue.startsWith(CURRENT_PREFIX)
              ? encryptedValue.substring(CURRENT_PREFIX.length())
              : encryptedValue;
      byte[] decoded = Base64.getDecoder().decode(payload);

      ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
      byte[] iv = new byte[IV_LENGTH_BYTE];
      byteBuffer.get(iv);

      byte[] cipherText = new byte[byteBuffer.remaining()];
      byteBuffer.get(cipherText);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      GCMParameterSpec spec = new GCMParameterSpec(TAG_LENGTH_BIT, iv);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

      byte[] plainText = cipher.doFinal(cipherText);
      return new String(plainText, StandardCharsets.UTF_8);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to decrypt field", ex);
    }
  }
}
