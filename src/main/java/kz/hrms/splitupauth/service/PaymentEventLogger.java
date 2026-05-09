package kz.hrms.splitupauth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import kz.hrms.splitupauth.entity.PaymentEventLog;
import kz.hrms.splitupauth.repository.PaymentEventLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentEventLogger {

    private final PaymentEventLogRepository repository;
    private final ObjectMapper objectMapper;

    public void log(String entityType, Long entityId, String eventType,
                    String fromStatus, String toStatus,
                    Long actorUserId, String correlationId, String idempotencyKey,
                    Map<String, Object> payload) {
        try {
            JsonNode payloadNode = payload == null ? null : objectMapper.valueToTree(payload);
            PaymentEventLog entry = PaymentEventLog.builder()
                    .entityType(entityType)
                    .entityId(entityId)
                    .eventType(eventType)
                    .fromStatus(fromStatus)
                    .toStatus(toStatus)
                    .actorUserId(actorUserId)
                    .correlationId(correlationId)
                    .idempotencyKey(idempotencyKey)
                    .payload(payloadNode)
                    .build();
            repository.save(entry);
        } catch (Exception ex) {
            // Logging must never break the main flow.
            log.error("Failed to write payment event log: {}", ex.getMessage(), ex);
        }
    }
}
