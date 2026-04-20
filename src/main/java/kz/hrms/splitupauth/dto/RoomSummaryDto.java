package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.RoomStatus;
import kz.hrms.splitupauth.entity.RoomType;
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
public class RoomSummaryDto {
    private Long id;
    private String title;
    private RoomType roomType;
    private RoomStatus status;
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