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
    name = "money_ledger_entries",
    indexes = {
      @Index(name = "idx_money_ledger_owner_created", columnList = "owner_user_id,created_at"),
      @Index(name = "idx_money_ledger_payment", columnList = "payment_intent_id,entry_type")
    },
    uniqueConstraints = {
      @UniqueConstraint(name = "uq_money_ledger_idempotency_key", columnNames = "idempotency_key")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MoneyLedgerEntry {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "entry_type", nullable = false, length = 30)
  private String entryType;

  @Column(nullable = false, precision = 12, scale = 2)
  private BigDecimal amount;

  @Column(nullable = false, length = 10)
  @Builder.Default
  private String currency = "KZT";

  @Column(nullable = false, length = 10)
  private String direction;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "payment_intent_id")
  private PaymentIntent paymentIntent;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "payment_transaction_id")
  private PaymentTransaction paymentTransaction;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "refund_transaction_id")
  private RefundTransaction refundTransaction;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "payout_id")
  private Payout payout;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_user_id")
  private User owner;

  @Column(name = "idempotency_key", nullable = false, length = 150)
  private String idempotencyKey;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
    if (currency == null) {
      currency = "KZT";
    }
  }
}
