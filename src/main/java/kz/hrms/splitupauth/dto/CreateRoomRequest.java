package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.*;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.PeriodType;
import kz.hrms.splitupauth.entity.RoomType;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class CreateRoomRequest {

    private Long categoryId;

    @NotNull(message = "Service id is required")
    private Long serviceId;

    private Long tariffPlanId;

    @NotNull(message = "Room type is required")
    private RoomType roomType;

    @NotBlank(message = "Title is required")
    @Size(max = 150, message = "Title must be at most 150 characters")
    private String title;

    private String description;

    @NotNull(message = "Max members is required")
    @Min(value = 2, message = "Max members must be at least 2")
    private Integer maxMembers;

    private BigDecimal priceTotal;

    private BigDecimal pricePerMember;

    private String currency;

    @NotNull(message = "Period type is required")
    private PeriodType periodType;

    @NotNull(message = "Start date is required")
    private LocalDateTime startDate;

    private String cancellationPolicy;

    private String providerName;

    private String tariffNameSnapshot;

    private ConnectionType connectionType;

    private String operatorRestrictions;

    private Boolean operatorTermsConfirmed;

    // Access type — may be omitted; inherited from the tariff's defaults when null (hybrid).
    private AccessType accessType;

    @Size(max = 10, message = "Region restriction must be at most 10 characters")
    private String regionRestriction;

    private Boolean requiresEmailForInvite;

    private Boolean emailChangeForbidden;

    @Min(value = 0, message = "Access grant SLA hours cannot be negative")
    private Integer accessGrantSlaHours;
}