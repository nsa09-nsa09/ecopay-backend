package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Data;

@Entity
@Table(name = "user_reports")
@Data
public class UserReport {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "reporter_user_id", nullable = false)
  private User reporter;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "target_user_id", nullable = false)
  private User targetUser;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserReportCategory category;

  @Column(nullable = false, length = 2000)
  private String description;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserReportStatus status;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "assigned_admin_id")
  private User assignedAdmin;

  @Column(columnDefinition = "TEXT")
  private String resolutionNote;

  @Column(nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  private LocalDateTime updatedAt;

  private LocalDateTime resolvedAt;

  @PrePersist
  void onCreate() {
    createdAt = LocalDateTime.now();
    updatedAt = createdAt;
  }

  @PreUpdate
  void onUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
