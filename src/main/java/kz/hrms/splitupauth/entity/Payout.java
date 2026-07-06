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
    name = "payouts",
    indexes = {
      @Index(name = "idx_payouts_user_status", columnList = "user_id, status"),
      @Index(name = "idx_payouts_status_created", columnList = "status, created_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payout {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id")
  private Room room;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "payout_method_id")
  private PayoutMethod payoutMethod;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 10)
  @Builder.Default
  private String currency = "KZT";

  /** PENDING | PROCESSING | SUCCESS | FAILED | PENDING_METHOD | CANCELED */
  @Column(nullable = false, length = 20)
  @Builder.Default
  private String status = "PENDING";

  @Column(name = "provider_payout_id", length = 150)
  private String providerPayoutId;

  @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
  private String idempotencyKey;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "triggering_payment_intent_id")
  private PaymentIntent triggeringPaymentIntent;

  @Column(name = "failure_reason", columnDefinition = "TEXT")
  private String failureReason;

  @Column(name = "retry_count", nullable = false)
  @Builder.Default
  private Integer retryCount = 0;

  /**
   * When this payout becomes eligible for dispatch. The member's charge is captured immediately,
   * but the owner payout is held until release_at (default created_at + 30d). The dispatcher
   * ignores payouts whose release_at is still in the future.
   */
  @Column(name = "release_at")
  private LocalDateTime releaseAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "processed_at")
  private LocalDateTime processedAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
    if (status == null) status = "PENDING";
    if (currency == null) currency = "KZT";
    if (retryCount == null) retryCount = 0;
    // Defensive: a payout with no explicit hold releases immediately.
    if (releaseAt == null) releaseAt = createdAt;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
