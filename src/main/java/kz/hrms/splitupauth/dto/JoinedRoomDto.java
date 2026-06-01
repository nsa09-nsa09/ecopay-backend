package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.MemberStatus;
import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Room summary enriched with the current user's membership info,
 * used for the "Joined rooms" listing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinedRoomDto {
    private Long roomId;
    private Long memberId;
    private String title;
    private RoomType roomType;
    private RoomStatus roomStatus;
    private MemberStatus memberStatus;
    private Boolean requiresAdminReview;
    private Integer maxMembers;
    private BigDecimal priceTotal;
    private BigDecimal pricePerMember;
    private String currency;
    private LocalDateTime startDate;
    private Long ownerUserId;
    private String ownerDisplayName;
    private Long serviceId;
    private String serviceName;
}
