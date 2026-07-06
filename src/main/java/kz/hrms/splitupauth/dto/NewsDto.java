package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.NewsStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Shape returned from /api/v1/news and /api/v1/admin/news. Public callers can ignore {@code
 * status}, {@code sortOrder} and {@code createdAt}; admin callers use them for triage.
 *
 * <p>{@code imageUrl} is rebuilt on every response from the stored object key and the current
 * request host (so links auto-match localhost vs prod without any config) — the row never persists
 * an absolute URL.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NewsDto {
  private Long id;

  private String titleKz;
  private String titleRu;
  private String titleEn;

  private String bodyKz;
  private String bodyRu;
  private String bodyEn;

  /** Backend-served URL, or {@code null} when no image attached. */
  private String imageUrl;

  private NewsStatus status;
  private LocalDateTime publishedAt;
  private Integer sortOrder;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
