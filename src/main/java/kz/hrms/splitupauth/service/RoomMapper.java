package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.RoomResponse;
import kz.hrms.splitupauth.dto.RoomSummaryDto;
import kz.hrms.splitupauth.entity.Room;
import org.springframework.stereotype.Component;

@Component
public class RoomMapper {

    public RoomResponse toResponse(Room room) {
        return RoomResponse.builder()
                .id(room.getId())
                .ownerUserId(room.getOwner().getId())
                .categoryId(room.getCategory() != null ? room.getCategory().getId() : null)
                .serviceId(room.getService().getId())
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
                .periodType(room.getPeriodType())
                .startDate(room.getStartDate())
                .cancellationPolicy(room.getCancellationPolicy())
                .providerName(room.getProviderName())
                .tariffNameSnapshot(room.getTariffNameSnapshot())
                .connectionType(room.getConnectionType())
                .operatorRestrictions(room.getOperatorRestrictions())
                .operatorTermsConfirmed(room.getOperatorTermsConfirmed())
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
                .startDate(room.getStartDate())
                .ownerUserId(room.getOwner().getId())
                .ownerDisplayName(room.getOwner().getDisplayName())
                .serviceId(room.getService().getId())
                .serviceName(room.getService().getName())
                .build();
    }
}