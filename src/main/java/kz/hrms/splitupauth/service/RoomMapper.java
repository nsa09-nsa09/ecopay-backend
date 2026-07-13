package kz.hrms.splitupauth.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.entity.ReputationLevel;
import kz.hrms.splitupauth.entity.Room;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RoomMapper {

  /**
   * Field-injected (mirrors {@link CatalogMapper}) so call sites that still {@code new
   * RoomMapper()} in unit tests keep compiling; when the logo storage isn't wired, {@code
   * serviceLogoUrl} simply comes back null.
   */
  @Autowired(required = false)
  private ServiceLogoStorageService logoStorage;

  /**
   * Field-injected for the same reason; null in plain unit tests → commission fields come back
   * null.
   */
  @Autowired(required = false)
  private CommissionCalculator commissionCalculator;

  private String serviceLogoUrl(Room room) {
    if (logoStorage == null || room.getService() == null) {
      return null;
    }
    return logoStorage.publicUrl(room.getService().getLogoKey());
  }

  /**
   * The per-member tariff share, mirroring PaymentService: explicit price, else priceTotal split.
   */
  private BigDecimal effectiveShare(Room room) {
    if (room.getPricePerMember() != null && room.getPricePerMember().signum() > 0) {
      return room.getPricePerMember();
    }
    if (room.getPriceTotal() != null
        && room.getPriceTotal().signum() > 0
        && room.getMaxMembers() != null
        && room.getMaxMembers() >= 2) {
      return room.getPriceTotal()
          .divide(BigDecimal.valueOf(room.getMaxMembers()), 2, RoundingMode.HALF_UP);
    }
    return null;
  }

  private BigDecimal memberCommission(Room room) {
    BigDecimal share = effectiveShare(room);
    if (commissionCalculator == null || share == null) {
      return null;
    }
    return commissionCalculator.commissionFor(share);
  }

  public RoomResponse toResponse(Room room) {
    BigDecimal commission = memberCommission(room);
    BigDecimal share = effectiveShare(room);
    BigDecimal total = (commission != null && share != null) ? share.add(commission) : null;
    return RoomResponse.builder()
        .id(room.getId())
        .ownerUserId(room.getOwner().getId())
        .ownerDisplayName(room.getOwner().getDisplayName())
        .ownerSlug(room.getOwner().getSlug())
        .ownerPublicId(room.getOwner().getPublicId())
        .ownerVerified(Boolean.TRUE.equals(room.getOwner().getOwnerVerified()))
        .ownerReputation(room.getOwner().getReputation())
        .ownerReputationLevel(ReputationLevel.fromScore(room.getOwner().getReputation()).name())
        .categoryId(room.getCategory() != null ? room.getCategory().getId() : null)
        .serviceId(room.getService().getId())
        .serviceLogoUrl(serviceLogoUrl(room))
        .tariffPlanId(room.getTariffPlan() != null ? room.getTariffPlan().getId() : null)
        .roomType(room.getRoomType())
        .verificationMode(room.getVerificationMode())
        .status(room.getStatus())
        .title(room.getTitle())
        .description(room.getDescription())
        .maxMembers(room.getMaxMembers())
        .priceTotal(room.getPriceTotal())
        .pricePerMember(room.getPricePerMember())
        .pricePerMemberCommission(commission)
        .pricePerMemberTotal(total)
        .currency(room.getCurrency())
        .fxRateToKzt(room.getFxRateToKzt())
        .priceTotalKzt(room.getPriceTotalKzt())
        .pricePerMemberKzt(room.getPricePerMemberKzt())
        .periodType(room.getPeriodType())
        .startDate(room.getStartDate())
        .cancellationPolicy(room.getCancellationPolicy())
        .providerName(room.getProviderName())
        .tariffNameSnapshot(room.getTariffNameSnapshot())
        .connectionType(room.getConnectionType())
        .operatorRestrictions(room.getOperatorRestrictions())
        .operatorTermsConfirmed(room.getOperatorTermsConfirmed())
        .accessType(room.getAccessType())
        .regionRestriction(room.getRegionRestriction())
        .requiresEmailForInvite(room.getRequiresEmailForInvite())
        .emailChangeForbidden(room.getEmailChangeForbidden())
        .accessGrantSlaHours(room.getAccessGrantSlaHours())
        .readyForVerificationAt(room.getReadyForVerificationAt())
        .completedAt(room.getCompletedAt())
        .blockedAt(room.getBlockedAt())
        .blockReason(room.getBlockReason())
        .createdAt(room.getCreatedAt())
        .updatedAt(room.getUpdatedAt())
        .build();
  }

  public RoomSummaryDto toSummary(Room room) {
    return RoomSummaryDto.builder()
        .id(room.getId())
        .title(room.getTitle())
        .roomType(room.getRoomType())
        .status(room.getStatus())
        .maxMembers(room.getMaxMembers())
        .priceTotal(room.getPriceTotal())
        .pricePerMember(room.getPricePerMember())
        .currency(room.getCurrency())
        .fxRateToKzt(room.getFxRateToKzt())
        .priceTotalKzt(room.getPriceTotalKzt())
        .pricePerMemberKzt(room.getPricePerMemberKzt())
        .startDate(room.getStartDate())
        .ownerUserId(room.getOwner().getId())
        .ownerDisplayName(room.getOwner().getDisplayName())
        .ownerSlug(room.getOwner().getSlug())
        .ownerPublicId(room.getOwner().getPublicId())
        .ownerVerified(Boolean.TRUE.equals(room.getOwner().getOwnerVerified()))
        .ownerReputation(room.getOwner().getReputation())
        .ownerReputationLevel(ReputationLevel.fromScore(room.getOwner().getReputation()).name())
        .serviceId(room.getService().getId())
        .serviceName(room.getService().getName())
        .serviceLogoUrl(serviceLogoUrl(room))
        .accessType(room.getAccessType())
        .regionRestriction(room.getRegionRestriction())
        .operatorRestrictions(room.getOperatorRestrictions())
        .tariffNameSnapshot(room.getTariffNameSnapshot())
        .periodType(room.getPeriodType())
        .build();
  }
}
