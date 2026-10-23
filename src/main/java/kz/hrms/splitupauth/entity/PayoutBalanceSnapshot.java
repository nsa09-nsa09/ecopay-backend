package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B1: an observation of the merchant payout balance at the provider. Written from the periodic
 * {@code /api/balance_status} check ({@code source=BALANCE_CHECK}) and from {@code pg_balance} in
 * every reg2reg payout response ({@code source=REG2REG}), so finance can see the balance over time.
 */
@Entity
@Table(name = "payout_balance_snapshots")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutBalanceSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "balance", precision = 14, scale = 2)
  private BigDecimal balance;

  @Column(name = "currency", length = 10)
  @Builder.Default
  private String currency = "KZT";

  /** {@code BALANCE_CHECK} (dedicated query) or {@code REG2REG} (balance carried in a payout). */
  @Column(name = "source", nullable = false, length = 20)
  private String source;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
    if (currency == null) {
      currency = "KZT";
    }
  }
}
