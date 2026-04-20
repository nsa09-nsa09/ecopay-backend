package kz.hrms.splitupauth.dto;

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
    private Long categoryId;
    private Long serviceId;
    private Long tariffPlanId;
    private RoomType roomType;
    private VerificationMode verificationMode;
    private RoomStatus status;
    private String title;
    private String description;
    private Integer maxMembers;
    private BigDecimal priceTotal;
    private BigDecimal pricePerMember;
    private String currency;
    private PeriodType periodType;
    private LocalDateTime startDate;
    private String cancellationPolicy;
    private String providerName;
    private String tariffNameSnapshot;
    private ConnectionType connectionType;
    private String operatorRestrictions;
    private Boolean operatorTermsConfirmed;
    private LocalDateTime readyForVerificationAt;
    private LocalDateTime completedAt;
    private LocalDateTime blockedAt;
    private String blockReason;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}