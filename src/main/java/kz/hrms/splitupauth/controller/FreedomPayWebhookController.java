package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.service.WebhookInboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/freedompay")
@RequiredArgsConstructor
@Slf4j
public class FreedomPayWebhookController {

    private final FreedomPayGateway gateway;
    private final WebhookInboxService inboxService;

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
        Map<String, String> safe = new HashMap<>(params);
        GatewayWebhookEvent event = gateway.verifyAndParseWebhook(script, safe);

        boolean signatureValid = gateway.verifyWebhookSignature(script, safe);

        // Inbox: UNIQUE(provider_request_id) dedups deliveries; processing + PROCESSED
        // mark are one transaction, and unprocessed rows are retried (see WebhookInboxService).
        WebhookInboxService.Outcome outcome;
        try {
            outcome = inboxService.receive(script, safe, event, signatureValid);
        } catch (Exception ex) {
            // The callback could not even be persisted: make the provider redeliver it
            // instead of acknowledging an event we have no record of.
            log.error("Freedom Pay webhook could not be recorded: {}", ex.getMessage(), ex);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }

        if (outcome == WebhookInboxService.Outcome.INVALID_SIGNATURE) {
            log.warn("Freedom Pay webhook signature invalid for {}", event.getProviderRequestId());
            return errorResponse(script, "invalid signature");
        }
        // PROCESSED, DUPLICATE, or DEFERRED (recorded; the inbox retry job will finish it).
        return okResponse(script);
    }

    // Freedom Pay requires the merchant reply to be signed (pg_salt + pg_sig).
    private ResponseEntity<String> okResponse(String script) {
        return ResponseEntity.ok(gateway.buildWebhookResponse(script, "ok", "Order processed"));
    }

    private ResponseEntity<String> errorResponse(String script, String description) {
        return ResponseEntity.ok(gateway.buildWebhookResponse(script, "error", description));
    }
}
