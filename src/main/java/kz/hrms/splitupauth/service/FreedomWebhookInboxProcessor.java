package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayMessage;
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

    Map<String, List<String>> params = toParams(inbox.getRawBody());
    String script =
        inbox.getCallbackScript() == null || inbox.getCallbackScript().isBlank()
            ? resolveLegacyScript(params)
            : inbox.getCallbackScript();

    FreedomPayMessage message = gateway.callbackMessage(params);
    // The signature is checked again right before any money is touched, independently of the
    // check done at accept time.
    if (!gateway.verifyCallback(script, message)) {
      throw new FreedomWebhookProcessingException(
          "INVALID_SIGNATURE", "Signature verification failed", false);
    }

    GatewayWebhookEvent event = gateway.parseCallback(script, message);
    String orderId = message.get("pg_order_id");
    if (FreedomPayGateway.CARD_STORAGE_RESULT_SCRIPT.equals(script)) {
      // Purchase card storage (cardstorage/add2). Its token is not proven payout-compatible, so it
      // can never become a payout destination; the binding is closed as "rebind required".
      cardBindingService.rejectLegacyPurchaseCardCallback(bindingIdFrom(orderId));
    } else if ("PAYOUT_CARD".equals(event.getKind())) {
      cardBindingService.applyPayoutCardWebhook(event);
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

  private static Long bindingIdFrom(String orderId) {
    if (orderId == null || !orderId.startsWith("cardbind-")) {
      return null;
    }
    return parseLongOrNull(orderId.substring("cardbind-".length()));
  }

  private static void validateEventIdentity(GatewayWebhookEvent event) {
    if ("CHARGE".equals(event.getKind()) && event.getIntentId() == null) {
      throw new FreedomWebhookProcessingException(
          "MISSING_INTENT_ID", "Charge webhook has no valid payment intent id", false);
    }
    if ("REFUND".equals(event.getKind())
        && (event.getExternalPaymentId() == null || event.getExternalPaymentId().isBlank())) {
      throw new FreedomWebhookProcessingException(
          "MISSING_PROVIDER_ID", "Money-operation webhook has no provider id", false);
    }
    if ("PAYOUT".equals(event.getKind())
        && (event.getExternalPaymentId() == null || event.getExternalPaymentId().isBlank())
        && (event.getOrderId() == null || event.getOrderId().isBlank())) {
      throw new FreedomWebhookProcessingException(
          "MISSING_PROVIDER_ID", "Payout webhook has neither provider id nor order id", false);
    }
  }

  /** Inbox rows store single values as strings and repeated values as arrays. */
  static Map<String, List<String>> toParams(JsonNode node) {
    Map<String, List<String>> map = new LinkedHashMap<>();
    if (node == null || !node.isObject()) return map;
    Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      JsonNode value = field.getValue();
      List<String> values = new ArrayList<>();
      if (value.isArray()) {
        value.forEach(v -> values.add(v.asText()));
      } else {
        values.add(value.asText());
      }
      map.put(field.getKey(), values);
    }
    return map;
  }

  private static String resolveLegacyScript(Map<String, List<String>> params) {
    return params.get("pg_payout_id") != null
            || List.of("PAYOUT").equals(params.get("pg_event_type"))
        ? FreedomPayGateway.PAYOUT_RESULT_SCRIPT
        : FreedomPayGateway.RESULT_SCRIPT;
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
