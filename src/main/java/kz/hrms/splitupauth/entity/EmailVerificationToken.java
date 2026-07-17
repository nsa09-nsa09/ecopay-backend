package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "email_verification_tokens",
    indexes = {@Index(name = "idx_verification_token", columnList = "token")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationToken {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String token;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  /**
   * BCrypt hash of the 6-digit code emailed to the user. Nullable for legacy rows created before
   * the code flow existed (those can still be confirmed via the {@link #token} link).
   */
  @Column(name = "code_hash")
  private String codeHash;

  /**
   * When set, this challenge adds/changes the account email: the address lives here until the code
   * (or link) is confirmed, and only then is copied to {@code users.email}. Null for the
   * registration-verification flow, where the address is already on the user row.
   */
  @Column(name = "pending_email")
  private String pendingEmail;

  /** Failed code attempts; the challenge is rejected once it reaches the service-layer limit. */
  @Column(nullable = false)
  @Builder.Default
  private Integer attempts = 0;

  @Column(name = "expires_at", nullable = false)
  private LocalDateTime expiresAt;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(nullable = false)
  @Builder.Default
  private Boolean used = false;

  @PrePersist
  protected void onCreate() {
    createdAt = LocalDateTime.now();
  }

  public boolean isExpired() {
    return LocalDateTime.now().isAfter(expiresAt);
  }
}
