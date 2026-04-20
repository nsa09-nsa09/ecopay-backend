package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.MyRoomMembershipDto;
import kz.hrms.splitupauth.dto.RoomMemberDto;
import kz.hrms.splitupauth.entity.RoomMember;
import kz.hrms.splitupauth.entity.RoomMemberIdentifier;
import org.springframework.stereotype.Component;

@Component
public class RoomMemberMapper {

    public RoomMemberDto toDto(RoomMember roomMember) {
        return RoomMemberDto.builder()
                .id(roomMember.getId())
                .roomId(roomMember.getRoom().getId())
                .userId(roomMember.getUser().getId())
                .userDisplayName(roomMember.getUser().getDisplayName())
                .userEmail(roomMember.getUser().getEmail())
                .status(roomMember.getStatus())
                .requiresAdminReview(roomMember.getRequiresAdminReview())
                .accessMethod(roomMember.getAccessMethod())
                .ownerAccessConfirmedAt(roomMember.getOwnerAccessConfirmedAt())
                .memberConfirmedAt(roomMember.getMemberConfirmedAt())
                .activatedAt(roomMember.getActivatedAt())
                .rejectedAt(roomMember.getRejectedAt())
                .endedAt(roomMember.getEndedAt())
                .consentAcceptedAt(roomMember.getConsentAcceptedAt())
                .createdAt(roomMember.getCreatedAt())
                .build();
    }

    public MyRoomMembershipDto toMyDto(RoomMember roomMember, RoomMemberIdentifier identifier) {
        return MyRoomMembershipDto.builder()
                .id(roomMember.getId())
                .roomId(roomMember.getRoom().getId())
                .userId(roomMember.getUser().getId())
                .status(roomMember.getStatus())
                .requiresAdminReview(roomMember.getRequiresAdminReview())
                .identifierType(identifier != null ? identifier.getIdentifierType() : null)
                .identifierMasked(identifier != null ? identifier.getIdentifierMasked() : null)
                .accessMethod(roomMember.getAccessMethod())
                .ownerAccessConfirmedAt(roomMember.getOwnerAccessConfirmedAt())
                .memberConfirmedAt(roomMember.getMemberConfirmedAt())
                .activatedAt(roomMember.getActivatedAt())
                .build();
    }
}