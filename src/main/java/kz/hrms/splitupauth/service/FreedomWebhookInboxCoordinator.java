package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/** Durable accept, lease orchestration, exponential retry, and dead-letter routing. */
@Service
@RequiredArgsConstructor
@Slf4j
public class FreedomWebhookInboxCoordinator {

  private final FreedomPayGateway gateway;
  private final ObjectMapper objectMapper;
  private final FreedomWebhookInboxTransactions transactions;
  private final FreedomWebhookInboxProcessor processor;
  private final ObjectProvider<MeterRegistry> meterRegistry;

  @Value("${app.webhooks.freedom.max-attempts:6}")
  private int maxAttempts;

  @Value("${app.webhooks.freedom.retry-base-seconds:30}")
  private long retryBaseSeconds;

  @Value("${app.webhooks.freedom.retry-max-seconds:3600}")
  private long retryMaxSeconds;

  @Value("${app.webhooks.freedom.lease-seconds:300}")
  private long leaseSeconds;

  @Value("${app.webhooks.freedom.batch-size:100}")
  private int batchSize;

  /** Single-valued convenience overload (tests, legacy callers). */
  public Acceptance acceptAndProcess(String script, Map<String, String> params) {
    Map<String, List<String>> multi = new LinkedHashMap<>();
    params.forEach((k, v) -> multi.put(k, v == null ? List.of() : List.of(v)));
    return acceptAndProcessMulti(script, multi);
  }

  public Acceptance acceptAndProcessMulti(String script, Map<String, List<String>> params) {
    Boolean signatureValid = null;
    GatewayWebhookEvent event = null;
    FreedomPayMessage message = null;
    String initialErrorCode = null;
    String initialErrorMessage = null;

    try {
      message = gateway.callbackMessage(params);
      signatureValid = gateway.verifyCallback(script, message);
      if (Boolean.TRUE.equals(signatureValid)) {
        event = gateway.parseCallback(script, message);
      }
    } catch (RuntimeException ex) {
      initialErrorCode = "ACCEPT_PARSE_FAILED";
      initialErrorMessage = ex.getClass().getSimpleName();
      log.warn(
          "Freedom webhook accepted for retry after verification/parse failure: {}",
          ex.getClass().getSimpleName());
    }

    boolean invalidSignature = Boolean.FALSE.equals(signatureValid);
    // A forged callback must never occupy the dedup key of a genuine one, so rows that failed
    // verification are keyed by their full raw content (salt and signature included).
    String requestId =
        event != null && event.getProviderRequestId() != null
            ? event.getProviderRequestId()
            : invalidSignature || message == null
                ? rawRequestId(script, params)
                : gateway.callbackRequestId(script, message);
    LocalDateTime now = LocalDateTime.now();

    FreedomWebhookInbox inbox =
        FreedomWebhookInbox.builder()
            .providerRequestId(requestId)
            .callbackScript(script)
            .rawBody(toJson(params))
            .signatureValid(signatureValid)
            .processingStatus(invalidSignature ? "DEAD_LETTER" : "PENDING")
            .attemptCount(invalidSignature ? 1 : 0)
            .lastAttemptAt(invalidSignature ? now : null)
            .processedAt(invalidSignature ? now : null)
            .deadLetteredAt(invalidSignature ? now : null)
            .lastErrorCode(invalidSignature ? "INVALID_SIGNATURE" : initialErrorCode)
            .errorMessage(invalidSignature ? "Signature verification failed" : initialErrorMessage)
            .build();

    FreedomWebhookInbox stored;
    boolean duplicate = false;
    try {
      stored = transactions.insert(inbox);
    } catch (DataIntegrityViolationException duplicateOrFailure) {
      stored =
          transactions.findByProviderRequestId(requestId).orElseThrow(() -> duplicateOrFailure);
      duplicate = true;
      log.info("Duplicate Freedom Pay webhook for script {}", script);
    }
    count(invalidSignature ? "invalid_signature" : duplicate ? "duplicate" : "accepted", script);

    boolean storedInvalidSignature =
        "DEAD_LETTER".equals(stored.getProcessingStatus())
            && "INVALID_SIGNATURE".equals(stored.getLastErrorCode());
    if (!storedInvalidSignature && !"PROCESSED".equals(stored.getProcessingStatus())) {
      processInbox(stored.getId());
    }
    return new Acceptance(stored.getId(), storedInvalidSignature);
  }

  private JsonNode toJson(Map<String, List<String>> params) {
    ObjectNode node = objectMapper.createObjectNode();
    params.forEach(
        (k, values) -> {
          if (values == null || values.isEmpty()) {
            node.put(k, "");
          } else if (values.size() == 1) {
            node.put(k, values.get(0));
          } else {
            ArrayNode array = node.putArray(k);
            values.forEach(array::add);
          }
        });
    return node;
  }

  private void count(String outcome, String script) {
    MeterRegistry registry = meterRegistry == null ? null : meterRegistry.getIfAvailable();
    if (registry != null) {
      registry
          .counter("ecopay.freedompay.webhook", "outcome", outcome, "callback", script)
          .increment();
    }
  }

  public void retryDueWebhooks() {
    LocalDateTime now = LocalDateTime.now();
    for (Long inboxId : transactions.findRetryableIds(now, batchSize)) {
      processInbox(inboxId);
    }
  }

  public void processInbox(Long inboxId) {
    LocalDateTime now = LocalDateTime.now();
    String leaseOwner = "webhook-" + UUID.randomUUID();
    OptionalInt claim =
        transactions.claim(inboxId, leaseOwner, now, now.plusSeconds(Math.max(1, leaseSeconds)));
    if (claim.isEmpty()) return;

    int attempt = claim.getAsInt();
    try {
      processor.processClaimed(inboxId, leaseOwner);
    } catch (FreedomWebhookProcessingException ex) {
      recordFailure(
          inboxId, leaseOwner, attempt, ex.isRetryable(), ex.getErrorCode(), ex.getMessage());
    } catch (RuntimeException ex) {
      recordFailure(
          inboxId, leaseOwner, attempt, true, ex.getClass().getSimpleName(), ex.getMessage());
    }
  }

  private void recordFailure(
      Long inboxId,
      String leaseOwner,
      int attempt,
      boolean retryable,
      String errorCode,
      String errorMessage) {
    long delay = backoffSeconds(attempt);
    try {
      transactions.recordFailure(
          inboxId,
          leaseOwner,
          attempt,
          maxAttempts,
          retryable,
          errorCode,
          errorMessage,
          LocalDateTime.now().plusSeconds(delay));
      if (!retryable || attempt >= Math.max(1, maxAttempts)) {
        log.error("Freedom webhook inbox {} moved to DEAD_LETTER: {}", inboxId, errorCode);
        count("dead_letter", "any");
      } else {
        count("retry_scheduled", "any");
        log.warn(
            "Freedom webhook inbox {} failed on attempt {}; retry in {}s: {}",
            inboxId,
            attempt,
            delay,
            errorCode);
      }
    } catch (RuntimeException stateFailure) {
      // The durable PROCESSING row is intentionally left leased. It will be reclaimed after
      // lease expiry if recording the failure itself is temporarily unavailable.
      log.error(
          "Failed to persist retry state for Freedom webhook {}: {}",
          inboxId,
          stateFailure.toString());
    }
  }

  private long backoffSeconds(int attempt) {
    long base = Math.max(1, retryBaseSeconds);
    long cap = Math.max(base, retryMaxSeconds);
    int exponent = Math.min(30, Math.max(0, attempt - 1));
    long multiplier = 1L << exponent;
    if (base > cap / multiplier) return cap;
    return Math.min(cap, base * multiplier);
  }

  private static String rawRequestId(String script, Map<String, List<String>> params) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(script.getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, List<String>> entry : new TreeMap<>(params).entrySet()) {
        List<String> values = entry.getValue() == null ? List.of() : entry.getValue();
        for (String value : values) {
          digest.update((byte) 0);
          digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
          digest.update((byte) '=');
          if (value != null) {
            digest.update(value.getBytes(StandardCharsets.UTF_8));
          }
        }
      }
      return "freedompay:raw:" + HexFormat.of().formatHex(digest.digest());
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  public record Acceptance(Long inboxId, boolean invalidSignature) {}
}
