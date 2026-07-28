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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "stories",
    indexes = {
      @Index(name = "idx_stories_status_sort", columnList = "status, sort_order, published_at"),
      @Index(name = "idx_stories_created_at", columnList = "created_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Story {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "title_kz", columnDefinition = "TEXT")
  private String titleKz;

  @Column(name = "title_ru", columnDefinition = "TEXT")
  private String titleRu;

  @Column(name = "title_en", columnDefinition = "TEXT")
  private String titleEn;

  @Column(name = "heading_kz", columnDefinition = "TEXT")
  private String headingKz;

  @Column(name = "heading_ru", columnDefinition = "TEXT")
  private String headingRu;

  @Column(name = "heading_en", columnDefinition = "TEXT")
  private String headingEn;

  @Column(name = "body_kz", columnDefinition = "TEXT")
  private String bodyKz;

  @Column(name = "body_ru", columnDefinition = "TEXT")
  private String bodyRu;

  @Column(name = "body_en", columnDefinition = "TEXT")
  private String bodyEn;

  @Column(name = "cta_label_kz", length = 120)
  private String ctaLabelKz;

  @Column(name = "cta_label_ru", length = 120)
  private String ctaLabelRu;

  @Column(name = "cta_label_en", length = 120)
  private String ctaLabelEn;

  @Column(name = "cta_url", length = 500)
  private String ctaUrl;

  @Column(name = "emoji", length = 32)
  private String emoji;

  @Column(name = "gradient", length = 255)
  private String gradient;

  @Column(name = "image_key", length = 255)
  private String imageKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private StoryStatus status;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(name = "sort_order", nullable = false)
  @Builder.Default
  private Integer sortOrder = 0;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "created_by")
  private User createdBy;

  @PrePersist
  void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
    if (status == null) {
      status = StoryStatus.DRAFT;
    }
    if (sortOrder == null) {
      sortOrder = 0;
    }
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
