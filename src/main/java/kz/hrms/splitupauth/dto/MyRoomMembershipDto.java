package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.IdentifierType;
import kz.hrms.splitupauth.entity.MemberStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MyRoomMembershipDto {
  private Long id;
  private Long roomId;
  private Long userId;
  private MemberStatus status;
  private Boolean requiresAdminReview;
  private IdentifierType identifierType;
  private String identifierMasked;
  private String accessMethod;
  private LocalDateTime ownerAccessConfirmedAt;
  private LocalDateTime memberConfirmedAt;
  private LocalDateTime activatedAt;
}
