package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A payer's claim. It is deliberately separate from a provider money operation. */
@Entity
@Table(
    name = "refund_requests",
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_refund_requests_idempotency", columnNames = "idempotency_key")
    },
    indexes = {
      @Index(name = "idx_refund_requests_status_created", columnList = "status,created_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payment_transaction_id", nullable = false)
  private PaymentTransaction paymentTransaction;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "requester_user_id", nullable = false)
  private User requester;

  @Enumerated(EnumType.STRING)
  @Column(name = "reason_code", nullable = false, length = 40)
  private RefundReasonCode reasonCode;

  @Column(nullable = false, length = 1000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 30)
  @Builder.Default
  private RefundRequestStatus status = RefundRequestStatus.REQUESTED;

  @Column(name = "approved_amount", precision = 12, scale = 2)
  private BigDecimal approvedAmount;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "decided_by_user_id")
  private User decidedBy;

  @Column(name = "decision_note", length = 1000)
  private String decisionNote;

  @Column(name = "idempotency_key", nullable = false, length = 100)
  private String idempotencyKey;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @Column(name = "decided_at")
  private LocalDateTime decidedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
    if (status == null) status = RefundRequestStatus.REQUESTED;
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
