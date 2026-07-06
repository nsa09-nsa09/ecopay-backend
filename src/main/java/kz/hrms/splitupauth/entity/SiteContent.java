package kz.hrms.splitupauth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Singleton "About Us" page content, editable from the admin panel. Only one row exists (id = 1,
 * enforced by DB CHECK constraint).
 */
@Entity
@Table(name = "site_content")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SiteContent {

  public static final long SINGLETON_ID = 1L;

  @Id private Long id;

  @Column(name = "company_name", nullable = false)
  private String companyName;

  @Column(nullable = false)
  private String title;

  @Column(columnDefinition = "TEXT")
  private String mission;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "contact_email")
  private String contactEmail;

  @Column(name = "contact_phone")
  private String contactPhone;

  // ----- Tri-lingual copy (V30). The legacy {title,mission,description}
  // columns above stay populated (mirrored from *_ru) for backward compat
  // with anything still reading them. -----

  @Column(name = "title_kz", columnDefinition = "TEXT")
  private String titleKz;

  @Column(name = "title_ru", columnDefinition = "TEXT")
  private String titleRu;

  @Column(name = "title_en", columnDefinition = "TEXT")
  private String titleEn;

  @Column(name = "mission_kz", columnDefinition = "TEXT")
  private String missionKz;

  @Column(name = "mission_ru", columnDefinition = "TEXT")
  private String missionRu;

  @Column(name = "mission_en", columnDefinition = "TEXT")
  private String missionEn;

  @Column(name = "description_kz", columnDefinition = "TEXT")
  private String descriptionKz;

  @Column(name = "description_ru", columnDefinition = "TEXT")
  private String descriptionRu;

  @Column(name = "description_en", columnDefinition = "TEXT")
  private String descriptionEn;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "updated_by")
  private User updatedBy;

  @PrePersist
  @PreUpdate
  void touchUpdatedAt() {
    this.updatedAt = LocalDateTime.now();
  }
}
