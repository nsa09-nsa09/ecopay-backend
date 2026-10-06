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
      @Index(name = "idx_login_attempts_ip_time", columnList = "ip_address, attempt_time")
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

  /** Source address as resolved by the trusted-proxy valve; null for legacy rows. */
  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @PrePersist
  protected void onCreate() {
    attemptTime = LocalDateTime.now();
  }
}
