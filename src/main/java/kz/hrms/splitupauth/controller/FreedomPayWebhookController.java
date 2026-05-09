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
        return processWebhook(params);
    }

    @PostMapping(value = "/payout-result", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> payoutResult(@RequestParam Map<String, String> params) {
        // Same envelope; the gateway differentiates internally.
        return processWebhook(params);
    }

    private ResponseEntity<String> processWebhook(Map<String, String> params) {
        Map<String, String> safe = new HashMap<>(params);
        GatewayWebhookEvent event = gateway.verifyAndParseWebhook(safe);

        boolean signatureValid = gateway.verifyWebhookSignature(safe);
        String requestId = event.getProviderRequestId();

        // Inbox dedup — UNIQUE(provider_request_id) guarantees once-only.
        try {
            FreedomWebhookInbox existing = inboxRepository
                    .findByProviderRequestId(requestId).orElse(null);
            if (existing != null) {
                log.info("Duplicate Freedom Pay webhook for {}, replying ok", requestId);
                return okResponse();
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
                return errorResponse("invalid signature");
            }

            paymentService.applyWebhookEvent(event);

            inbox.setProcessingStatus("PROCESSED");
            inbox.setProcessedAt(LocalDateTime.now());
            inboxRepository.save(inbox);
            return okResponse();
        } catch (Exception ex) {
            log.error("Freedom Pay webhook handler failed: {}", ex.getMessage(), ex);
            // Reply ok to avoid endless retries; inbox row remains PENDING for offline retry.
            return okResponse();
        }
    }

    private static ResponseEntity<String> okResponse() {
        return ResponseEntity.ok("<response><pg_status>ok</pg_status></response>");
    }

    private static ResponseEntity<String> errorResponse(String description) {
        return ResponseEntity.ok(
                "<response><pg_status>error</pg_status><pg_description>"
                        + description + "</pg_description></response>");
    }
}
