package kz.hrms.splitupauth.controller;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.service.FreedomWebhookInboxCoordinator;
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

  private static final int MAX_WEBHOOK_PARAM_BYTES = 32_768;

  private final FreedomPayGateway gateway;
  private final FreedomWebhookInboxCoordinator inboxCoordinator;

  @PostMapping(value = "/result", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> result(@RequestParam Map<String, String> params) {
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

    try {
      FreedomWebhookInboxCoordinator.Acceptance accepted =
          inboxCoordinator.acceptAndProcess(script, new HashMap<>(params));
      if (accepted.invalidSignature()) {
        log.warn("Freedom Pay webhook signature invalid; inbox={}", accepted.inboxId());
        return errorResponse(script, "invalid signature");
      }
      return okResponse(script);
    } catch (RuntimeException ex) {
      // Never acknowledge a callback that was not durably stored. Freedom Pay will retry it.
      log.error("Freedom Pay webhook could not be stored: {}", ex.getMessage(), ex);
      return errorResponse(script, "temporarily unavailable");
    }
  }

  private boolean payloadTooLarge(Map<String, String> params) {
    long bytes = 0;
    for (Map.Entry<String, String> entry : params.entrySet()) {
      bytes +=
          entry.getKey() == null
              ? 0
              : entry.getKey().getBytes(StandardCharsets.UTF_8).length;
      bytes +=
          entry.getValue() == null
              ? 0
              : entry.getValue().getBytes(StandardCharsets.UTF_8).length;
      if (bytes > MAX_WEBHOOK_PARAM_BYTES) return true;
    }
    return false;
  }

  private ResponseEntity<String> okResponse(String script) {
    return ResponseEntity.ok(gateway.buildWebhookResponse(script, "ok", "Order processed"));
  }

  private ResponseEntity<String> errorResponse(String script, String description) {
    return ResponseEntity.ok(gateway.buildWebhookResponse(script, "error", description));
  }
}
