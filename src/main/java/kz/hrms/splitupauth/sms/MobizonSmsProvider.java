package kz.hrms.splitupauth.sms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(prefix = "ecopay.sms", name = "provider", havingValue = "mobizon")
public class MobizonSmsProvider implements SmsService {

  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final String baseUrl;
  private final String apiKey;
  private final String from;

  public MobizonSmsProvider(
      ObjectMapper objectMapper,
      @Value("${ecopay.sms.mobizon.base-url}") String baseUrl,
      @Value("${ecopay.sms.mobizon.api-key}") String apiKey,
      @Value("${ecopay.sms.mobizon.from:}") String from) {
    this.objectMapper = objectMapper;
    this.baseUrl = stripTrailingSlash(baseUrl);
    this.apiKey = apiKey;
    this.from = from;
    this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  }

  @Override
  public void sendVerificationCode(String phone, String code) {
    String body =
        "recipient="
            + encode(normalizePhone(phone))
            + "&text="
            + encode("EcoPay verification code: " + code)
            + "&from="
            + encode(from);
    URI uri =
        URI.create(
            baseUrl
                + "/service/message/sendSmsMessage?output=json&api=v1&apiKey="
                + encode(apiKey));
    HttpRequest request =
        HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("content-type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();

    try {
      HttpResponse<String> response =
          httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new SmsDeliveryException(
            "Mobizon SMS request failed with HTTP " + response.statusCode());
      }
      JsonNode root = objectMapper.readTree(response.body());
      int codeValue = root.path("code").asInt(-1);
      if (codeValue != 0) {
        throw new SmsDeliveryException("Mobizon SMS request was rejected");
      }
      String messageId = root.path("data").path("messageId").asText("");
      log.info("Mobizon verification SMS accepted for phone={} messageId={}", phone, messageId);
    } catch (IOException e) {
      throw new SmsDeliveryException("Mobizon SMS request failed", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new SmsDeliveryException("Mobizon SMS request interrupted", e);
    }
  }

  private static String normalizePhone(String phone) {
    return phone == null ? "" : phone.replaceAll("[^0-9]", "");
  }

  private static String encode(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String stripTrailingSlash(String value) {
    if (value == null || value.isBlank()) {
      return "";
    }
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }
}
