package kz.hrms.splitupauth.scheduler;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import kz.hrms.splitupauth.service.PaymentService;
import kz.hrms.splitupauth.service.PayoutCardBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
@Slf4j
public class FreedomWebhookRetryScheduler {

  private final FreedomWebhookInboxRepository inboxRepository;
  private final FreedomPayGateway gateway;
  private final PaymentService paymentService;
  private final PayoutCardBindingService cardBindingService;

  @Value("${app.webhooks.freedom.max-attempts:6}")
  private int maxAttempts;

  @Scheduled(fixedDelayString = "${app.webhooks.freedom.retry-delay-ms:60000}")
  public void retryDueWebhooks() {
    for (FreedomWebhookInbox inbox : inboxRepository.findRetryable(LocalDateTime.now())) {
      try {
        processInbox(inbox.getId());
      } catch (Exception ex) {
        log.warn("Freedom webhook retry {} failed outside handler: {}", inbox.getId(), ex.toString());
      }
    }
  }

  @Transactional
  public void processInbox(Long inboxId) {
    FreedomWebhookInbox inbox = inboxRepository.findWithLockById(inboxId).orElse(null);
    if (inbox == null
        || "PROCESSED".equals(inbox.getProcessingStatus())
        || "DEAD_LETTER".equals(inbox.getProcessingStatus())) {
      return;
    }

    LocalDateTime now = LocalDateTime.now();
    inbox.setProcessingStatus("PROCESSING");
    inbox.setLeaseUntil(now.plusMinutes(5));
    inbox.setAttemptCount((inbox.getAttemptCount() == null ? 0 : inbox.getAttemptCount()) + 1);
    inboxRepository.save(inbox);

    try {
      Map<String, String> params = toStringMap(inbox.getRawBody());
      String script = resolveScript(params);
      if (!gateway.verifyWebhookSignature(script, params)) {
        markDead(inbox, "INVALID_SIGNATURE", "Signature verification failed");
        return;
      }

      GatewayWebhookEvent event = gateway.verifyAndParseWebhook(script, params);
      String orderId = params.get("pg_order_id");
      if (orderId != null && orderId.startsWith("cardbind-")) {
        Long bindingId = parseLongOrNull(orderId.substring("cardbind-".length()));
        boolean success = "1".equals(params.get("pg_result")) || "SUCCESS".equals(event.getResultStatus());
        cardBindingService.applyBindingWebhook(
            bindingId, success, event.getCardToken(), event.getCardPanMask());
      } else {
        paymentService.applyWebhookEvent(event);
      }

      inbox.setProcessingStatus("PROCESSED");
      inbox.setProcessedAt(LocalDateTime.now());
      inbox.setLeaseUntil(null);
      inbox.setNextRetryAt(null);
      inbox.setLastErrorCode(null);
      inbox.setErrorMessage(null);
      inboxRepository.save(inbox);
    } catch (Exception ex) {
      int attempts = inbox.getAttemptCount() == null ? 1 : inbox.getAttemptCount();
      if (attempts >= maxAttempts) {
        markDead(inbox, "MAX_ATTEMPTS", ex.getMessage());
      } else {
        inbox.setProcessingStatus("FAILED");
        inbox.setLastErrorCode(ex.getClass().getSimpleName());
        inbox.setErrorMessage(ex.getMessage());
        inbox.setLeaseUntil(null);
        inbox.setNextRetryAt(LocalDateTime.now().plusSeconds(backoffSeconds(attempts)));
        inboxRepository.save(inbox);
      }
    }
  }

  private void markDead(FreedomWebhookInbox inbox, String code, String message) {
    inbox.setProcessingStatus("DEAD_LETTER");
    inbox.setLastErrorCode(code);
    inbox.setErrorMessage(message);
    inbox.setProcessedAt(LocalDateTime.now());
    inbox.setLeaseUntil(null);
    inbox.setNextRetryAt(null);
    inboxRepository.save(inbox);
    log.error("Freedom webhook inbox {} moved to DEAD_LETTER: {}", inbox.getId(), code);
  }

  private long backoffSeconds(int attempts) {
    return Math.min(3600L, (long) Math.pow(2, Math.max(0, attempts - 1)) * 30L);
  }

  private String resolveScript(Map<String, String> params) {
    return params.get("pg_payout_id") != null || "PAYOUT".equals(params.get("pg_event_type"))
        ? "payout-result"
        : "result";
  }

  private Map<String, String> toStringMap(JsonNode node) {
    Map<String, String> map = new HashMap<>();
    if (node == null || !node.isObject()) {
      return map;
    }
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      map.put(field.getKey(), field.getValue().asText());
    }
    return map;
  }

  private static Long parseLongOrNull(String s) {
    if (s == null || s.isBlank()) return null;
    try {
      return Long.parseLong(s.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
