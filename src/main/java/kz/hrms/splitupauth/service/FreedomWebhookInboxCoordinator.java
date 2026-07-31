package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;
import java.util.UUID;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  public Acceptance acceptAndProcess(String script, Map<String, String> params) {
    Boolean signatureValid = null;
    GatewayWebhookEvent event = null;
    String initialErrorCode = null;
    String initialErrorMessage = null;

    try {
      signatureValid = gateway.verifyWebhookSignature(script, params);
      if (Boolean.TRUE.equals(signatureValid)) {
        event = gateway.verifyAndParseWebhook(script, params);
      }
    } catch (RuntimeException ex) {
      initialErrorCode = "ACCEPT_PARSE_FAILED";
      initialErrorMessage = ex.getMessage();
      log.warn("Freedom webhook accepted for retry after verification/parse failure: {}", ex.toString());
    }

    String requestId =
        event != null && event.getProviderRequestId() != null
            ? event.getProviderRequestId()
            : rawRequestId(script, params);
    LocalDateTime now = LocalDateTime.now();
    boolean invalidSignature = Boolean.FALSE.equals(signatureValid);

    FreedomWebhookInbox inbox =
        FreedomWebhookInbox.builder()
            .providerRequestId(requestId)
            .callbackScript(script)
            .rawBody(objectMapper.valueToTree(params))
            .signatureValid(signatureValid)
            .processingStatus(invalidSignature ? "DEAD_LETTER" : "PENDING")
            .attemptCount(invalidSignature ? 1 : 0)
            .lastAttemptAt(invalidSignature ? now : null)
            .processedAt(invalidSignature ? now : null)
            .deadLetteredAt(invalidSignature ? now : null)
            .lastErrorCode(invalidSignature ? "INVALID_SIGNATURE" : initialErrorCode)
            .errorMessage(
                invalidSignature ? "Signature verification failed" : initialErrorMessage)
            .build();

    FreedomWebhookInbox stored;
    try {
      stored = transactions.insert(inbox);
    } catch (DataIntegrityViolationException duplicateOrFailure) {
      stored =
          transactions
              .findByProviderRequestId(requestId)
              .orElseThrow(() -> duplicateOrFailure);
      log.info("Duplicate Freedom Pay webhook for {}", requestId);
    }

    boolean storedInvalidSignature =
        "DEAD_LETTER".equals(stored.getProcessingStatus())
            && "INVALID_SIGNATURE".equals(stored.getLastErrorCode());
    if (!storedInvalidSignature && !"PROCESSED".equals(stored.getProcessingStatus())) {
      processInbox(stored.getId());
    }
    return new Acceptance(stored.getId(), storedInvalidSignature);
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
        transactions.claim(
            inboxId, leaseOwner, now, now.plusSeconds(Math.max(1, leaseSeconds)));
    if (claim.isEmpty()) return;

    int attempt = claim.getAsInt();
    try {
      processor.processClaimed(inboxId, leaseOwner);
    } catch (FreedomWebhookProcessingException ex) {
      recordFailure(
          inboxId,
          leaseOwner,
          attempt,
          ex.isRetryable(),
          ex.getErrorCode(),
          ex.getMessage());
    } catch (RuntimeException ex) {
      recordFailure(
          inboxId,
          leaseOwner,
          attempt,
          true,
          ex.getClass().getSimpleName(),
          ex.getMessage());
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
      } else {
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

  private static String rawRequestId(String script, Map<String, String> params) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(script.getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, String> entry : new TreeMap<>(params).entrySet()) {
        digest.update((byte) 0);
        digest.update(entry.getKey().getBytes(StandardCharsets.UTF_8));
        digest.update((byte) '=');
        if (entry.getValue() != null) {
          digest.update(entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
      }
      return "freedompay:raw:" + HexFormat.of().formatHex(digest.digest());
    } catch (Exception impossible) {
      throw new IllegalStateException("SHA-256 is unavailable", impossible);
    }
  }

  public record Acceptance(Long inboxId, boolean invalidSignature) {}
}
