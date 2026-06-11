package kz.hrms.splitupauth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import kz.hrms.splitupauth.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/freedompay")
@RequiredArgsConstructor
@Slf4j
public class FreedomPayWebhookController {

    private final FreedomPayGateway gateway;
    private final FreedomWebhookInboxRepository inboxRepository;
    private final PaymentService paymentService;
    private final ObjectMapper objectMapper;

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
        String requestId = event.getProviderRequestId();

        // Inbox dedup — UNIQUE(provider_request_id) guarantees once-only.
        try {
            FreedomWebhookInbox existing = inboxRepository
                    .findByProviderRequestId(requestId).orElse(null);
            if (existing != null) {
                log.info("Duplicate Freedom Pay webhook for {}, replying ok", requestId);
                return okResponse(script);
            }
            FreedomWebhookInbox inbox = FreedomWebhookInbox.builder()
                    .providerRequestId(requestId)
                    .rawBody(objectMapper.valueToTree(safe))
                    .signatureValid(signatureValid)
                    .processingStatus("PENDING")
                    .build();
            inboxRepository.save(inbox);

            if (!signatureValid) {
                inbox.setProcessingStatus("INVALID_SIGNATURE");
                inbox.setProcessedAt(LocalDateTime.now());
                inboxRepository.save(inbox);
                log.warn("Freedom Pay webhook signature invalid for {}", requestId);
                return errorResponse(script, "invalid signature");
            }

            paymentService.applyWebhookEvent(event);

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

    // Freedom Pay requires the merchant reply to be signed (pg_salt + pg_sig).
    private ResponseEntity<String> okResponse(String script) {
        return ResponseEntity.ok(gateway.buildWebhookResponse(script, "ok", "Order processed"));
    }

    private ResponseEntity<String> errorResponse(String script, String description) {
        return ResponseEntity.ok(gateway.buildWebhookResponse(script, "error", description));
    }
}
