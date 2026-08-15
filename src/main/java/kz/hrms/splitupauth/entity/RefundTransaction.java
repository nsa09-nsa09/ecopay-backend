package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "refund_transactions")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundTransaction {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_transaction_id", nullable = false)
  private PaymentTransaction paymentTransaction;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "dispute_id")
  private Dispute dispute;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "admin_user_id")
  private User adminUser;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private RefundStatus status;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 10)
  @Builder.Default
  private String currency = "KZT";

  @Column(nullable = false, length = 255)
  private String reason;

  @Column(name = "idempotency_key", nullable = false, unique = true, length = 100)
  private String idempotencyKey;

  @Column(name = "provider_refund_id", length = 150)
  private String providerRefundId;

  @Column(name = "retry_count", nullable = false)
  @Builder.Default
  private Integer retryCount = 0;

  @Column(name = "next_retry_at")
  private LocalDateTime nextRetryAt;

  @Column(name = "claimed_at")
  private LocalDateTime claimedAt;

  @Column(name = "lease_until")
  private LocalDateTime leaseUntil;

  @Column(name = "last_error_code", length = 80)
  private String lastErrorCode;

  @Column(name = "last_error_message", columnDefinition = "TEXT")
  private String lastErrorMessage;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (status == null) {
      status = RefundStatus.PENDING;
    }
    if (currency == null) {
      currency = "KZT";
    }
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
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
