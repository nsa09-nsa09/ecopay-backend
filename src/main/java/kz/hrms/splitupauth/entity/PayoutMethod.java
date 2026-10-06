package kz.hrms.splitupauth.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "payout_methods")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayoutMethod {

  /** Token produced by FreedomPay payout-card tokenization (cardstoragepayout/add). */
  public static final String TOKEN_SOURCE_PAYOUT_CARD = "PAYOUT_CARD_TOKEN";

  public static final String STATUS_ACTIVE = "ACTIVE";

  /** Legacy method whose token is not proven payout-compatible; never dispatched to. */
  public static final String STATUS_REQUIRES_REBIND = "REQUIRES_REBIND";

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "user_id", nullable = false)
  private User user;

  @Column(name = "provider_name", nullable = false, length = 50)
  private String providerName;

  @Column(name = "provider_card_token", nullable = false, length = 255)
  private String providerCardToken;

  @Column(name = "pan_mask", length = 20)
  private String panMask;

  @Column(name = "verified_at")
  private LocalDateTime verifiedAt;

  /** Origin of the token; only {@link #TOKEN_SOURCE_PAYOUT_CARD} may receive payouts. */
  @Column(name = "token_source", length = 40)
  private String tokenSource;

  public boolean isPayoutCompatible() {
    return STATUS_ACTIVE.equals(status) && TOKEN_SOURCE_PAYOUT_CARD.equals(tokenSource);
  }

  @Column(name = "is_default", nullable = false)
  @Builder.Default
  private Boolean isDefault = false;

  @Column(nullable = false, length = 20)
  @Builder.Default
  private String status = "ACTIVE";

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @PrePersist
  protected void onCreate() {
    if (createdAt == null) createdAt = LocalDateTime.now();
    if (status == null) status = "ACTIVE";
    if (isDefault == null) isDefault = false;
  }
}
