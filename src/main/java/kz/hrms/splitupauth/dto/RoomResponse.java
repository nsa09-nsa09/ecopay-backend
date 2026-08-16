package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceAccessType;
import kz.hrms.splitupauth.entity.VerificationMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomResponse {
  private Long id;
  private Long ownerUserId;
  private String ownerDisplayName;
  private String ownerSlug;
  private String ownerPublicId;
  private Boolean ownerVerified;
  private Integer ownerReputation;
  private String ownerReputationLevel;
  private Double ownerRating;
  private Integer ownerReviewCount;
  private Long categoryId;
  private Long serviceId;

  /** Backend-served URL of the service logo (S3-backed), or null if none uploaded. */
  private String serviceLogoUrl;

  /**
   * What a joining member must hand over for this service — EMAIL, PHONE or BOTH. Named apart from
   * {@link #accessType}, which is the room's own "how does the owner grant access" field.
   */
  private ServiceAccessType serviceAccessType;

  private Long tariffPlanId;
  private RoomType roomType;
  private VerificationMode verificationMode;
  private RoomStatus status;
  private String title;
  private String description;
  private Integer maxMembers;
  private Integer filledSeats;
  private Integer freeSeats;
  private BigDecimal priceTotal;
  private BigDecimal pricePerMember;

  /** EcoPay commission a joining member pays on top of {@link #pricePerMember}. */
  private BigDecimal pricePerMemberCommission;

  /** Total a joining member pays = pricePerMember + pricePerMemberCommission. */
  private BigDecimal pricePerMemberTotal;

  /** KZT amount the owner receives for one joining member. */
  private BigDecimal shareKzt;

  /** EcoPay commission in the settlement currency. */
  private BigDecimal commissionKzt;

  /** Total charged to the member in the settlement currency. */
  private BigDecimal payableTotalKzt;

  /** Currency used by the payment gateway for this room's member checkout. */
  private String settlementCurrency;

  /** Source per-member tariff share before KZT settlement conversion. */
  private BigDecimal originalTariffPrice;

  private String originalTariffCurrency;
  private BigDecimal fxRateSnapshot;

  private String currency;

  /** Snapshot of the price→KZT rate used at room creation (1 for KZT). */
  private BigDecimal fxRateToKzt;

  /** Snapshot of price_total expressed in KZT at creation. */
  private BigDecimal priceTotalKzt;

  /** Snapshot of price_per_member expressed in KZT at creation. */
  private BigDecimal pricePerMemberKzt;

  private PeriodType periodType;
  private LocalDateTime startDate;
  private String cancellationPolicy;
  private String providerName;
  private String tariffNameSnapshot;
  private ConnectionType connectionType;
  private String operatorRestrictions;
  private Boolean operatorTermsConfirmed;
  private AccessType accessType;
  private String regionRestriction;
  private Boolean requiresEmailForInvite;
  private Boolean emailChangeForbidden;
  private Integer accessGrantSlaHours;
  private LocalDateTime readyForVerificationAt;
  private LocalDateTime completedAt;
  private LocalDateTime blockedAt;
  private String blockReason;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
