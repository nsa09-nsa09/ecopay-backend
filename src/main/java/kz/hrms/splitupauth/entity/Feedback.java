package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "feedback",
    indexes = {
      @Index(name = "idx_feedback_status_created_at", columnList = "status,created_at"),
      @Index(name = "idx_feedback_user_created_at", columnList = "user_id,created_at"),
      @Index(name = "idx_feedback_type", columnList = "type")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Feedback {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private FeedbackType type;

  @Column(length = 150)
  private String subject;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String message;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  @Builder.Default
  private FeedbackStatus status = FeedbackStatus.NEW;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "admin_note", columnDefinition = "TEXT")
  private String adminNote;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "handled_by")
  private User handledBy;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at")
  private LocalDateTime updatedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
    if (status == null) {
      status = FeedbackStatus.NEW;
    }
  }

  @PreUpdate
  protected void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
