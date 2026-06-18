package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.entity.ReputationLevel;
import kz.hrms.splitupauth.entity.Room;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class RoomMapper {

    /**
     * Field-injected (mirrors {@link CatalogMapper}) so call sites that still
     * {@code new RoomMapper()} in unit tests keep compiling; when the logo
     * storage isn't wired, {@code serviceLogoUrl} simply comes back null.
     */
    @Autowired(required = false)
    private ServiceLogoStorageService logoStorage;

    private String serviceLogoUrl(Room room) {
        if (logoStorage == null || room.getService() == null) {
            return null;
        }
        return logoStorage.publicUrl(room.getService().getLogoKey());
    }

    public RoomResponse toResponse(Room room) {
        return RoomResponse.builder()
                .id(room.getId())
                .ownerUserId(room.getOwner().getId())
                .ownerDisplayName(room.getOwner().getDisplayName())
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