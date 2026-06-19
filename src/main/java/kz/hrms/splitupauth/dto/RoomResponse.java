package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.VerificationMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomResponse {
    private Long id;
    private Long ownerUserId;
    private String ownerDisplayName;
    private Boolean ownerVerified;
    private Integer ownerReputation;
    private String ownerReputationLevel;
    private Double ownerRating;
    private Integer ownerReviewCount;
    private Long categoryId;
    private Long serviceId;
    /** Backend-served URL of the service logo (S3-backed), or null if none uploaded. */
    private String serviceLogoUrl;
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