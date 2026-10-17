package kz.hrms.splitupauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Narrow "the price changed" ledger. One row per detected transition of a provider's observed
 * price. Powers the admin change-feed (unacknowledged={@code true}) so a human can review before
 * dismissing.
 */
@Entity
@Table(
    name = "price_change",
    indexes = {
      @Index(name = "idx_price_change_ack_changed", columnList = "acknowledged, changed_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceChange {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "provider_id", nullable = false)
  private PriceWatchProvider provider;

  @Column(name = "old_price", precision = 12, scale = 2)
  private BigDecimal oldPrice;

  @Column(name = "new_price", nullable = false, precision = 12, scale = 2)
  private BigDecimal newPrice;

  @Column(name = "currency", length = 10)
  private String currency;

  @Column(name = "changed_at", nullable = false)
  private LocalDateTime changedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "snapshot_id")
  private PriceSnapshot snapshot;

  @Column(name = "acknowledged", nullable = false)
  @Builder.Default
  private Boolean acknowledged = false;

  @PrePersist
  void onCreate() {
    if (changedAt == null) {
      changedAt = LocalDateTime.now();
    }
    if (acknowledged == null) {
      acknowledged = false;
    }
  }
}
