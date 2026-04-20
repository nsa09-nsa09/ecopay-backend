package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.*;
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
}