package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "login_attempts",
    indexes = {
      @Index(name = "idx_email_attempt", columnList = "email, attempt_time"),
      @Index(name = "idx_login_attempt_ip", columnList = "ip, attempt_time")
    })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginAttempt {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false)
  private String email;

  @Column(name = "attempt_time", nullable = false)
  private LocalDateTime attemptTime;

  @Column(nullable = false)
  private Boolean successful;

  /** Source IP (from the trusted proxy chain) — backs the per-IP credential-stuffing throttle. */
  @Column(name = "ip", length = 64)
  private String ip;

  @PrePersist
  protected void onCreate() {
    attemptTime = LocalDateTime.now();
  }
}
