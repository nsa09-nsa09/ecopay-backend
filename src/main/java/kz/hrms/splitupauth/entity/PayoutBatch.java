package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "payout_batches",
    indexes = {
      @Index(name = "idx_payout_batches_status_retry", columnList = "status,next_retry_at"),
      @Index(name = "idx_payout_batches_owner_status", columnList = "owner_user_id,status")
    },
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_payout_batches_idempotency", columnNames = "idempotency_key"),
      @UniqueConstraint(name = "uq_payout_batches_provider_id", columnNames = "provider_payout_id")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutBatch {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "owner_user_id", nullable = false)
  private User owner;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payout_method_id", nullable = false)
  private PayoutMethod payoutMethod;

  @Column(nullable = false, length = 10)
  @Builder.Default
  private String currency = "KZT";

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 20)
  @Builder.Default
  private String status = "PROCESSING";

  @Column(name = "provider_payout_id", length = 150)
  private String providerPayoutId;

  @Column(name = "idempotency_key", nullable = false, length = 100)
  private String idempotencyKey;

  @Column(name = "provider_order_id", length = 50)
  private String providerOrderId;

  @Column(name = "destination_card_token", length = 255)
  private String destinationCardToken;

  @Column(name = "provider_name", length = 50)
  private String providerName;

  /** Committed before network I/O: from this point the outcome can be ambiguous. */
  @Column(name = "submission_started_at")
  private LocalDateTime submissionStartedAt;

  @Column(name = "retry_count", nullable = false)
  @Builder.Default
  private Integer retryCount = 0;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  @Column(name = "next_retry_at")
  private LocalDateTime nextRetryAt;

  @Column(name = "lease_until")
  private LocalDateTime leaseUntil;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "processed_at")
  private LocalDateTime processedAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
    if (currency == null) {
      currency = "KZT";
    }
    if (status == null) {
      status = "PROCESSING";
    }
    if (retryCount == null) {
      retryCount = 0;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
