package kz.hrms.splitupauth.controller;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.service.FreedomWebhookInboxCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FreedomPay server-to-server callbacks. Every callback is stored durably in the inbox (signature
 * checked first; an invalid one is dead-lettered without touching money) before it is acknowledged.
 * Parameters are kept multi-valued so a signed repeated field is never silently collapsed.
 *
 * <p>Browser redirects (success/failure URLs) are NOT handled here and are never proof of payment.
 */
@RestController
@RequestMapping("/api/v1/webhooks/freedompay")
@RequiredArgsConstructor
@Slf4j
public class FreedomPayWebhookController {

  private static final int MAX_WEBHOOK_PARAM_BYTES = 32_768;

  private final FreedomPayGateway gateway;
  private final FreedomWebhookInboxCoordinator inboxCoordinator;

  @PostMapping(value = "/result", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> result(@RequestParam MultiValueMap<String, String> params) {
    return processWebhook(FreedomPayGateway.RESULT_SCRIPT, params);
  }

  @PostMapping(value = "/payout-result", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> payoutResult(@RequestParam MultiValueMap<String, String> params) {
    return processWebhook(FreedomPayGateway.PAYOUT_RESULT_SCRIPT, params);
  }

  /** Callback for payout-card tokenization ({@code cardstoragepayout/add}). */
  @PostMapping(value = "/payout-card-result", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> payoutCardResult(
      @RequestParam MultiValueMap<String, String> params) {
    return processWebhook(FreedomPayGateway.PAYOUT_CARD_RESULT_SCRIPT, params);
  }

  /**
   * Legacy callback for purchase card storage ({@code cardstorage/add2}). Still accepted and
   * recorded so in-flight provider retries are acknowledged, but such tokens are never registered
   * as payout destinations any more.
   */
  @PostMapping(value = "/card-storage-result", produces = MediaType.APPLICATION_XML_VALUE)
  public ResponseEntity<String> cardStorageResult(
      @RequestParam MultiValueMap<String, String> params) {
    return processWebhook(FreedomPayGateway.CARD_STORAGE_RESULT_SCRIPT, params);
  }

  private ResponseEntity<String> processWebhook(
      String script, MultiValueMap<String, String> params) {
    if (payloadTooLarge(params)) {
      log.warn("Freedom Pay {} callback rejected before inbox store: payload too large", script);
      return errorResponse(script, "payload too large");
    }

    Map<String, List<String>> copy = new LinkedHashMap<>();
    params.forEach((k, v) -> copy.put(k, v == null ? List.of() : new ArrayList<>(v)));
    try {
      FreedomWebhookInboxCoordinator.Acceptance accepted =
          inboxCoordinator.acceptAndProcessMulti(script, copy);
      if (accepted.invalidSignature()) {
        log.warn("Freedom Pay webhook signature invalid; inbox={}", accepted.inboxId());
        return errorResponse(script, "invalid signature");
      }
      return okResponse(script);
    } catch (RuntimeException ex) {
      // Never acknowledge a callback that was not durably stored. Freedom Pay will retry it.
      log.error("Freedom Pay webhook could not be stored: {}", ex.getClass().getSimpleName());
      return errorResponse(script, "temporarily unavailable");
    }
  }

  private boolean payloadTooLarge(MultiValueMap<String, String> params) {
    long bytes = 0;
    for (Map.Entry<String, List<String>> entry : params.entrySet()) {
      bytes += entry.getKey() == null ? 0 : entry.getKey().getBytes(StandardCharsets.UTF_8).length;
      if (entry.getValue() != null) {
        for (String value : entry.getValue()) {
          bytes += value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length;
        }
      }
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
