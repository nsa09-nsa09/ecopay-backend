package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Per-(user, type) channel opt-out. Absence of a row means every channel is on (lazy default), so
 * new users need no back-fill. A row is created only when a user changes a default — see {@code
 * NotificationPreferenceService}.
 */
@Entity
@Table(
    name = "notification_preferences",
    uniqueConstraints = {
      @UniqueConstraint(
          name = "uq_notification_pref_user_type",
          columnNames = {"user_id", "type"})
    },
    indexes = {@Index(name = "idx_notification_prefs_user", columnList = "user_id")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationPreference {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 50)
  private NotificationType type;

  @Column(name = "in_app", nullable = false)
  @Builder.Default
  private boolean inApp = true;

  @Column(nullable = false)
  @Builder.Default
  private boolean email = true;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  @PreUpdate
  protected void touch() {
    updatedAt = LocalDateTime.now();
  }
}
