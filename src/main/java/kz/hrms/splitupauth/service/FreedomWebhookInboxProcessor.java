package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Applies a claimed webhook and marks it processed in the same database transaction. */
@Service
@RequiredArgsConstructor
public class FreedomWebhookInboxProcessor {

  private final FreedomWebhookInboxRepository inboxRepository;
  private final FreedomPayGateway gateway;
  private final PaymentService paymentService;
  private final PayoutCardBindingService cardBindingService;

  @Transactional
  public boolean processClaimed(Long inboxId, String leaseOwner) {
    FreedomWebhookInbox inbox =
        inboxRepository.findClaimedWithLockById(inboxId, leaseOwner).orElse(null);
    if (inbox == null) return false;

    Map<String, String> params = toStringMap(inbox.getRawBody());
    String script =
        inbox.getCallbackScript() == null || inbox.getCallbackScript().isBlank()
            ? resolveLegacyScript(params)
            : inbox.getCallbackScript();

    if (!gateway.verifyWebhookSignature(script, params)) {
      throw new FreedomWebhookProcessingException(
          "INVALID_SIGNATURE", "Signature verification failed", false);
    }

    GatewayWebhookEvent event = gateway.verifyAndParseWebhook(script, params);
    String orderId = params.get("pg_order_id");
    if (orderId != null && orderId.startsWith("cardbind-")) {
      Long bindingId = parseLongOrNull(orderId.substring("cardbind-".length()));
      if (bindingId == null) {
        throw new FreedomWebhookProcessingException(
            "INVALID_BINDING_ID", "Card-binding webhook has an invalid order id", false);
      }
      boolean success =
          "1".equals(params.get("pg_result")) || "SUCCESS".equals(event.getResultStatus());
      cardBindingService.applyBindingWebhook(
          bindingId, success, event.getCardToken(), event.getCardPanMask());
    } else {
      validateEventIdentity(event);
      paymentService.applyWebhookEvent(event);
    }

    inbox.setSignatureValid(true);
    inbox.setProcessingStatus("PROCESSED");
    inbox.setProcessedAt(LocalDateTime.now());
    inbox.setLeaseOwner(null);
    inbox.setLeaseUntil(null);
    inbox.setNextRetryAt(null);
    inbox.setLastErrorCode(null);
    inbox.setErrorMessage(null);
    inbox.setDeadLetteredAt(null);
    inboxRepository.save(inbox);
    return true;
  }

  private static void validateEventIdentity(GatewayWebhookEvent event) {
    if ("CHARGE".equals(event.getKind()) && event.getIntentId() == null) {
      throw new FreedomWebhookProcessingException(
          "MISSING_INTENT_ID", "Charge webhook has no valid payment intent id", false);
    }
    if (("PAYOUT".equals(event.getKind()) || "REFUND".equals(event.getKind()))
        && (event.getExternalPaymentId() == null || event.getExternalPaymentId().isBlank())) {
      throw new FreedomWebhookProcessingException(
          "MISSING_PROVIDER_ID", "Money-operation webhook has no provider id", false);
    }
  }

  private static Map<String, String> toStringMap(JsonNode node) {
    Map<String, String> map = new HashMap<>();
    if (node == null || !node.isObject()) return map;
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      map.put(field.getKey(), field.getValue().asText());
    }
    return map;
  }

  private static String resolveLegacyScript(Map<String, String> params) {
    return params.get("pg_payout_id") != null || "PAYOUT".equals(params.get("pg_event_type"))
        ? "payout-result"
        : "result";
  }

  private static Long parseLongOrNull(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }
}
