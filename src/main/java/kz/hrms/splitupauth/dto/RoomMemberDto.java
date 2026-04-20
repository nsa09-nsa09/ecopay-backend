package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.MemberStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomMemberDto {
    private Long id;
    private Long roomId;
    private Long userId;
    private String userDisplayName;
    private String userEmail;
    private MemberStatus status;
    private Boolean requiresAdminReview;
    private String accessMethod;
    private LocalDateTime ownerAccessConfirmedAt;
    private LocalDateTime memberConfirmedAt;
    private LocalDateTime activatedAt;
    private LocalDateTime rejectedAt;
    private LocalDateTime endedAt;
    private LocalDateTime consentAcceptedAt;
    private LocalDateTime createdAt;
}