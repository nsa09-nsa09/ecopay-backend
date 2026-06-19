package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.*;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.RoomType;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateRoomRequest {

    private Long categoryId;

    @NotNull(message = "Service id is required")
    private Long serviceId;

    // Pricing-critical fields (price, currency, billing period, seat count) are
    // owned by the admin-managed tariff plan — not the room owner. Selecting a
    // tariff is therefore mandatory, and the room snapshots those values from it
    // at creation time. The owner cannot override them here.
    @NotNull(message = "Tariff plan is required")
    private Long tariffPlanId;

    @NotNull(message = "Room type is required")
    private RoomType roomType;

    @NotBlank(message = "Title is required")
    @Size(max = 150, message = "Title must be at most 150 characters")
    private String title;

    private String description;

    @NotNull(message = "Start date is required")
    @Future(message = "Start date must be in the future")
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
