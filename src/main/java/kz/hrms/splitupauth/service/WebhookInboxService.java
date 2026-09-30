package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.hrms.splitupauth.entity.FreedomWebhookInbox;
import kz.hrms.splitupauth.payment.gateway.GatewayWebhookEvent;
import kz.hrms.splitupauth.payment.gateway.freedom.FreedomPayGateway;
import kz.hrms.splitupauth.repository.FreedomWebhookInboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Transactional inbox for provider callbacks: at-least-once delivery from the
 * provider becomes exactly-once processing.
 *
 * <ul>
 *   <li>The callback is persisted (unique provider_request_id) before anything else.</li>
 *   <li>Applying the event and marking the row PROCESSED happen in ONE
 *       transaction under the row lock — a crash can never leave an applied
 *       event marked unprocessed, nor a processed row whose effects rolled back.</li>
 *   <li>A row that is not PROCESSED is re-processed when the provider redelivers
 *       it, and by {@link #retryUnprocessed()} if it never does. The business
 *       handlers are themselves idempotent (final states, unique capture rows).</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WebhookInboxService {

    private static final int MAX_ATTEMPTS = 10;
    private static final int RETRY_BATCH = 100;

    public enum Outcome { PROCESSED, DUPLICATE, INVALID_SIGNATURE, DEFERRED }

    private final FreedomWebhookInboxRepository inboxRepository;
    private final PaymentService paymentService;
    private final FreedomPayGateway gateway;
    private final ObjectMapper objectMapper;

    private TransactionTemplate txTemplate;

    @Autowired
    void setTransactionManager(PlatformTransactionManager transactionManager) {
        this.txTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Persists and processes one delivery. Throws only when the callback could
     * not be persisted — the caller must then make the provider retry.
     */
    public Outcome receive(String endpoint, Map<String, String> params, GatewayWebhookEvent event, boolean signatureValid) {
        Long inboxId = recordOrFind(endpoint, params, event.getProviderRequestId(), signatureValid);
        return process(inboxId, event);
    }

    private Long recordOrFind(String endpoint, Map<String, String> params, String requestId, boolean signatureValid) {
        try {
            return txTemplate.execute(s -> inboxRepository.findByProviderRequestId(requestId)
                    .map(FreedomWebhookInbox::getId)
                    .orElseGet(() -> inboxRepository.saveAndFlush(FreedomWebhookInbox.builder()
                            .providerRequestId(requestId)
                            .endpoint(endpoint)
                            .rawBody(objectMapper.valueToTree(params))
                            .signatureValid(signatureValid)
                            .processingStatus("PENDING")
                            .build()).getId()));
        } catch (DataIntegrityViolationException concurrentDelivery) {
            // The same callback is being recorded by a parallel delivery: use its row.
            return txTemplate.execute(s -> inboxRepository.findByProviderRequestId(requestId)
                    .map(FreedomWebhookInbox::getId)
                    .orElseThrow(() -> concurrentDelivery));
        }
    }

    private Outcome process(Long inboxId, GatewayWebhookEvent event) {
        try {
            return txTemplate.execute(s -> {
                FreedomWebhookInbox inbox = inboxRepository.findWithLockById(inboxId).orElseThrow();
                if ("PROCESSED".equals(inbox.getProcessingStatus())) {
                    log.info("Duplicate Freedom Pay webhook {}, already processed", inbox.getProviderRequestId());
                    return Outcome.DUPLICATE;
                }
                if (!Boolean.TRUE.equals(inbox.getSignatureValid())) {
                    inbox.setProcessingStatus("INVALID_SIGNATURE");
                    inbox.setProcessedAt(LocalDateTime.now());
                    inboxRepository.save(inbox);
                    return Outcome.INVALID_SIGNATURE;
                }
                paymentService.applyWebhookEvent(event);
                inbox.setAttempts(inbox.getAttempts() + 1);
                inbox.setProcessingStatus("PROCESSED");
                inbox.setProcessedAt(LocalDateTime.now());
                inbox.setErrorMessage(null);
                inboxRepository.save(inbox);
                return Outcome.PROCESSED;
            });
        } catch (RuntimeException ex) {
            log.error("Webhook inbox row {} processing failed, will retry: {}", inboxId, ex.getMessage(), ex);
            txTemplate.executeWithoutResult(s -> inboxRepository.findWithLockById(inboxId).ifPresent(inbox -> {
                if (!"PROCESSED".equals(inbox.getProcessingStatus())) {
                    inbox.setProcessingStatus("FAILED");
                    inbox.setAttempts(inbox.getAttempts() + 1);
                    inbox.setErrorMessage(String.valueOf(ex.getMessage()));
                    inboxRepository.save(inbox);
                }
            }));
            return Outcome.DEFERRED;
        }
    }

    /** Re-processes callbacks whose processing crashed and that the provider did not redeliver. */
    @Scheduled(fixedDelayString = "${app.webhooks.inbox-retry-delay-ms:60000}")
    public int retryUnprocessed() {
        List<Long> ids = inboxRepository.findRetryableIds(List.of("PENDING", "FAILED"),
                LocalDateTime.now().minusMinutes(1), MAX_ATTEMPTS, PageRequest.of(0, RETRY_BATCH));
        int processed = 0;
        for (Long id : ids) {
            GatewayWebhookEvent event = txTemplate.execute(s -> inboxRepository.findById(id)
                    .map(this::reparse)
                    .orElse(null));
            if (event != null && process(id, event) == Outcome.PROCESSED) {
                processed++;
            }
        }
        return processed;
    }

    private GatewayWebhookEvent reparse(FreedomWebhookInbox inbox) {
        Map<String, String> params = objectMapper.convertValue(inbox.getRawBody(), new TypeReference<Map<String, String>>() {});
        String endpoint = inbox.getEndpoint();
        if (endpoint == null) {
            // Rows recorded before the endpoint column existed.
            endpoint = params.containsKey("pg_payout_id") ? "payout-result" : "result";
        }
        return gateway.verifyAndParseWebhook(endpoint, params);
    }
}
