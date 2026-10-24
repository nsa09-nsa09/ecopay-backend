package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "payout_blocks",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_payout_blocks_source",
          columnNames = {"payout_id", "source_type", "source_id"})
    },
    indexes = {@Index(name = "idx_payout_blocks_active", columnList = "payout_id,status")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutBlock {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "payout_id", nullable = false)
  private Payout payout;

  @Enumerated(EnumType.STRING)
  @Column(name = "source_type", nullable = false, length = 30)
  private PayoutBlockSourceType sourceType;

  @Column(name = "source_id", nullable = false)
  private Long sourceId;

  @Column(name = "reason_code", nullable = false, length = 80)
  private String reasonCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private PayoutBlockStatus status = PayoutBlockStatus.ACTIVE;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "released_at")
  private LocalDateTime releasedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
    if (status == null) status = PayoutBlockStatus.ACTIVE;
  }
}
