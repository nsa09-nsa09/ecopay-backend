package kz.hrms.splitupauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import kz.hrms.splitupauth.service.PaymentService;
import kz.hrms.splitupauth.service.PayoutCardBindingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/webhooks/freedompay")
@RequiredArgsConstructor
@Slf4j
public class FreedomPayWebhookController {

  private final FreedomPayGateway gateway;
  private final FreedomWebhookInboxRepository inboxRepository;
  private final PaymentService paymentService;
  private final PayoutCardBindingService cardBindingService;
  private final ObjectMapper objectMapper;
  private static final int MAX_WEBHOOK_PARAM_BYTES = 32_768;

  @PostMapping(value = "/result", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> result(@RequestParam Map<String, String> params) {
    // Freedom Pay signs callbacks with the last path segment of the result URL.
    return processWebhook("result", params);
  }

  @PostMapping(value = "/payout-result", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> payoutResult(@RequestParam Map<String, String> params) {
    return processWebhook("payout-result", params);
  }

  private ResponseEntity<String> processWebhook(String script, Map<String, String> params) {
    if (payloadTooLarge(params)) {
      log.warn("Freedom Pay webhook rejected before inbox store: payload too large");
      return errorResponse(script, "payload too large");
    }
    Map<String, String> safe = new HashMap<>(params);
    GatewayWebhookEvent event = gateway.verifyAndParseWebhook(script, safe);

    boolean signatureValid = gateway.verifyWebhookSignature(script, safe);
    String requestId = event.getProviderRequestId();

    // Inbox dedup — UNIQUE(provider_request_id) guarantees once-only.
    try {
      FreedomWebhookInbox existing =
          inboxRepository.findByProviderRequestId(requestId).orElse(null);
      if (existing != null) {
        log.info("Duplicate Freedom Pay webhook for {}, replying ok", requestId);
        return okResponse(script);
      }
      FreedomWebhookInbox inbox =
          FreedomWebhookInbox.builder()
              .providerRequestId(requestId)
              .rawBody(objectMapper.valueToTree(safe))
              .signatureValid(signatureValid)
              .processingStatus("PENDING")
              .build();
      inboxRepository.save(inbox);

      if (!signatureValid) {
        inbox.setProcessingStatus("DEAD_LETTER");
        inbox.setAttemptCount(1);
        inbox.setLastErrorCode("INVALID_SIGNATURE");
        inbox.setProcessedAt(LocalDateTime.now());
        inboxRepository.save(inbox);
        log.warn("Freedom Pay webhook signature invalid for {}", requestId);
        return errorResponse(script, "invalid signature");
      }

      // Payout-card binding callbacks carry a "cardbind-{id}" order id (kept out of the
      // numeric PaymentIntent space). Route them to the binding finalizer; everything else
      // is a normal charge/refund/payout event.
      String orderId = safe.get("pg_order_id");
      if (orderId != null && orderId.startsWith("cardbind-")) {
        Long bindingId = parseLongOrNull(orderId.substring("cardbind-".length()));
        boolean success =
            "1".equals(safe.get("pg_result")) || "SUCCESS".equals(event.getResultStatus());
        cardBindingService.applyBindingWebhook(
            bindingId, success, event.getCardToken(), event.getCardPanMask());
      } else {
        paymentService.applyWebhookEvent(event);
      }

      inbox.setProcessingStatus("PROCESSED");
      inbox.setProcessedAt(LocalDateTime.now());
      inboxRepository.save(inbox);
      return okResponse(script);
    } catch (Exception ex) {
      log.error("Freedom Pay webhook handler failed: {}", ex.getMessage(), ex);
      // Reply ok to avoid endless retries; inbox row remains PENDING for offline retry.
      return okResponse(script);
    }
  }

  private boolean payloadTooLarge(Map<String, String> params) {
    int bytes = 0;
    for (Map.Entry<String, String> entry : params.entrySet()) {
      bytes += entry.getKey() == null ? 0 : entry.getKey().length();
      bytes += entry.getValue() == null ? 0 : entry.getValue().length();
      if (bytes > MAX_WEBHOOK_PARAM_BYTES) {
        return true;
      }
    }
    return false;
  }

  // Freedom Pay requires the merchant reply to be signed (pg_salt + pg_sig).
  private ResponseEntity<String> okResponse(String script) {
    return ResponseEntity.ok(gateway.buildWebhookResponse(script, "ok", "Order processed"));
  }

  private ResponseEntity<String> errorResponse(String script, String description) {
    return ResponseEntity.ok(gateway.buildWebhookResponse(script, "error", description));
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
