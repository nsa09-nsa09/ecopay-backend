package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Tracks an owner's attempt to connect a payout card through the Freedom Pay hosted page.
 * A small verification charge (auto-refunded) is used to tokenize the card; this row links
 * the resulting provider payment id back to the user so the confirmation step can only
 * register a token the same user actually produced.
 */
@Entity
@Table(name = "payout_card_bindings", indexes = {
        @Index(name = "idx_payout_card_bindings_user_status", columnList = "user_id, status")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutCardBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "provider_name", nullable = false, length = 50)
    private String providerName;

    @Column(name = "external_payment_id", length = 150)
    private String externalPaymentId;

    /** PENDING | SUCCESS | FAILED */
    @Column(nullable = false, length = 20)
    @Builder.Default
    private String status = "PENDING";

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 10)
    @Builder.Default
    private String currency = "KZT";

    @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
    private String idempotencyKey;

    @Column(name = "pan_mask", length = 20)
    private String panMask;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_method_id")
    private PayoutMethod payoutMethod;

    @Column(name = "failure_message", columnDefinition = "TEXT")
    private String failureMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (status == null) status = "PENDING";
        if (currency == null) currency = "KZT";
    }
}
