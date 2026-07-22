package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import kz.hrms.splitupauth.entity.ServiceAccessType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomSummaryDto {
  private Long id;
  private String title;
  private RoomType roomType;
  private RoomStatus status;
  private Integer maxMembers;
  private Integer filledSeats;
  private Integer freeSeats;
  private BigDecimal priceTotal;
  private BigDecimal pricePerMember;
  private String currency;
  private BigDecimal fxRateToKzt;
  private BigDecimal priceTotalKzt;
  private BigDecimal pricePerMemberKzt;
  private LocalDateTime startDate;
  private Long ownerUserId;
  private String ownerDisplayName;
  private String ownerSlug;
  private String ownerPublicId;
  private Boolean ownerVerified;
  private Integer ownerReputation;
  private String ownerReputationLevel;
  private Double ownerRating;
  private Integer ownerReviewCount;
  private Long serviceId;
  private String serviceName;

  /** Backend-served URL of the service logo (S3-backed), or null if none uploaded. */
  private String serviceLogoUrl;

  /** What a joining member must hand over for this service — EMAIL, PHONE or BOTH. */
  private ServiceAccessType serviceAccessType;

  private AccessType accessType;
  private String regionRestriction;
  private String operatorRestrictions;
  private String tariffNameSnapshot;
  private PeriodType periodType;
}
