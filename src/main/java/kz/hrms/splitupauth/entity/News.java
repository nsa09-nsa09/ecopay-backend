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

/**
 * Editorial news item surfaced on the landing page. Tri-lingual copy (kk/ru/en) mirrors the V30
 * site_content shape — every field is optional per language so the admin can publish a partially
 * translated draft.
 *
 * <p>{@code imageKey} is the S3 object key written by {@code NewsImageStorageService}; never an
 * absolute URL. The bytes are streamed back through the backend just like avatars (see {@code
 * PublicAvatarController}).
 */
@Entity
@Table(
    name = "news",
    indexes = {
      @Index(name = "idx_news_status_published_at", columnList = "status, published_at"),
      @Index(name = "idx_news_sort_created_at", columnList = "sort_order, created_at")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class News {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "title_kz", columnDefinition = "TEXT")
  private String titleKz;

  @Column(name = "title_ru", columnDefinition = "TEXT")
  private String titleRu;

  @Column(name = "title_en", columnDefinition = "TEXT")
  private String titleEn;

  @Column(name = "body_kz", columnDefinition = "TEXT")
  private String bodyKz;

  @Column(name = "body_ru", columnDefinition = "TEXT")
  private String bodyRu;

  @Column(name = "body_en", columnDefinition = "TEXT")
  private String bodyEn;

  @Column(name = "image_key", length = 255)
  private String imageKey;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private NewsStatus status;

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
      status = NewsStatus.DRAFT;
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
