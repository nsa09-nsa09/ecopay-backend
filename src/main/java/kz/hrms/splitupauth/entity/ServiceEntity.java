package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "services",
    indexes = {@Index(name = "idx_services_category_id", columnList = "category_id")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "category_id", nullable = false)
  private Category category;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(nullable = false, unique = true, length = 120)
  private String slug;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider_type", nullable = false)
  private ProviderType providerType;

  /**
   * What a joining member must supply so the owner can add them — email, phone, or either. Driven
   * by the provider's own rules (Spotify invites by email, operators need the number).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "access_type", nullable = false, length = 10)
  @Builder.Default
  private ServiceAccessType accessType = ServiceAccessType.EMAIL;

  @Column(name = "is_active", nullable = false)
  @Builder.Default
  private Boolean isActive = true;

  /** S3 object key under {@code service-logos/}; null when no logo uploaded. */
  @Column(name = "logo_key", length = 255)
  private String logoKey;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
    if (isActive == null) {
      isActive = true;
    }
    if (accessType == null) {
      accessType = ServiceAccessType.EMAIL;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
