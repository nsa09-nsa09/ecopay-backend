package kz.hrms.splitupauth.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One tracked subscription price: platform, plan, the URL that carries the price and the recipe for
 * lifting it out of the HTML. Health fields ({@code status}, {@code consecutiveFailures}, {@code
 * nextCheckAt}) are owned by the scheduler and mutate as observations come in.
 *
 * <p>See {@code PriceWatchService} for the state machine and {@code PriceExtractor} for the
 * extraction strategies keyed by {@link #extractorType}.
 */
@Entity
@Table(
    name = "price_watch_provider",
    indexes = {
      @Index(
          name = "idx_price_watch_provider_active_next_check",
          columnList = "active, next_check_at"),
      @Index(name = "idx_price_watch_provider_platform", columnList = "platform_code")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceWatchProvider {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "platform_code", nullable = false, length = 64)
  private String platformCode;

  @Column(name = "display_name", nullable = false, length = 200)
  private String displayName;

  @Column(name = "plan_name", nullable = false, length = 200)
  private String planName;

  @Column(name = "url", columnDefinition = "TEXT")
  private String url;

  @Column(name = "locale", length = 20)
  private String locale;

  @Column(name = "expected_currency", length = 10)
  private String expectedCurrency;

  @Enumerated(EnumType.STRING)
  @Column(name = "extractor_type", nullable = false, length = 20)
  private PriceExtractorType extractorType;

  /** Selector / regex / JSON-LD path knobs. Shape is extractor-specific. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "extractor_config", nullable = false, columnDefinition = "jsonb")
  private JsonNode extractorConfig;

  @Column(name = "requires_js", nullable = false)
  @Builder.Default
  private Boolean requiresJs = false;

  @Column(name = "check_interval_minutes", nullable = false)
  @Builder.Default
  private Integer checkIntervalMinutes = 720;

  @Column(name = "active", nullable = false)
  @Builder.Default
  private Boolean active = true;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  @Builder.Default
  private PriceWatchStatus status = PriceWatchStatus.PENDING;

  @Column(name = "consecutive_failures", nullable = false)
  @Builder.Default
  private Integer consecutiveFailures = 0;

  @Column(name = "last_checked_at")
  private LocalDateTime lastCheckedAt;

  @Column(name = "last_success_at")
  private LocalDateTime lastSuccessAt;

  @Column(name = "next_check_at")
  private LocalDateTime nextCheckAt;

  @Column(name = "last_price", precision = 12, scale = 2)
  private BigDecimal lastPrice;

  @Column(name = "last_currency", length = 10)
  private String lastCurrency;

  @Column(name = "lease_owner", length = 100)
  private String leaseOwner;

  @Column(name = "lease_until")
  private LocalDateTime leaseUntil;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void onCreate() {
    LocalDateTime now = LocalDateTime.now();
    if (createdAt == null) {
      createdAt = now;
    }
    if (updatedAt == null) {
      updatedAt = now;
    }
    if (extractorType == null) {
      extractorType = PriceExtractorType.AUTO;
    }
    if (extractorConfig == null) {
      extractorConfig = JsonNodeFactory.instance.objectNode();
    }
    if (status == null) {
      status = PriceWatchStatus.PENDING;
    }
    if (requiresJs == null) {
      requiresJs = false;
    }
    if (active == null) {
      active = true;
    }
    if (checkIntervalMinutes == null) {
      checkIntervalMinutes = 720;
    }
    if (consecutiveFailures == null) {
      consecutiveFailures = 0;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
