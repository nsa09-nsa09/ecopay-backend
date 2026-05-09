package kz.hrms.splitupauth.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;

@Entity
@Table(name = "freedom_webhook_inbox", indexes = {
        @Index(name = "idx_freedom_webhook_inbox_status", columnList = "processing_status, received_at")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FreedomWebhookInbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_request_id", nullable = false, unique = true, length = 200)
    private String providerRequestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_body", nullable = false, columnDefinition = "jsonb")
    private JsonNode rawBody;

    @Column(name = "signature_valid")
    private Boolean signatureValid;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "processing_status", nullable = false, length = 20)
    @Builder.Default
    private String processingStatus = "PENDING";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @PrePersist
    protected void onCreate() {
        if (receivedAt == null) receivedAt = LocalDateTime.now();
        if (processingStatus == null) processingStatus = "PENDING";
    }
}
