package kz.hrms.splitupauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One fetch attempt against a {@link PriceWatchProvider}. Recorded regardless of outcome — failures
 * (parse, network, blocked) are just as useful for diagnosing broken extractors as successes are
 * for price history. {@code rawExcerpt} is a truncated body slice kept for troubleshooting, never a
 * full page dump.
 */
@Entity
@Table(
    name = "price_snapshot",
    indexes = {
      @Index(name = "idx_price_snapshot_provider_captured", columnList = "provider_id, captured_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceSnapshot {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "provider_id", nullable = false)
  private PriceWatchProvider provider;

  @Column(name = "price", precision = 12, scale = 2)
  private BigDecimal price;

  @Column(name = "currency", length = 10)
  private String currency;

  @Column(name = "captured_at", nullable = false)
  private LocalDateTime capturedAt;

  @Enumerated(EnumType.STRING)
  @Column(name = "outcome", nullable = false, length = 40)
  private PriceSnapshotOutcome outcome;

  @Column(name = "http_status")
  private Integer httpStatus;

  @Column(name = "raw_excerpt", columnDefinition = "TEXT")
  private String rawExcerpt;

  @Column(name = "body_hash", length = 64)
  private String bodyHash;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @PrePersist
  void onCreate() {
    if (capturedAt == null) {
      capturedAt = LocalDateTime.now();
    }
  }
}
