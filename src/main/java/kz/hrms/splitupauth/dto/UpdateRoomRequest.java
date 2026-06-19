package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.AccessType;
import kz.hrms.splitupauth.entity.ConnectionType;
import lombok.Data;

@Data
public class UpdateRoomRequest {

    @Size(max = 150, message = "Title must be at most 150 characters")
    private String title;

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    // Price, currency, billing period, and seat count are tariff-controlled
    // (set by the admin on the tariff plan) and intentionally NOT editable here.

    @Size(max = 1000, message = "Cancellation policy must be at most 1000 characters")
    private String cancellationPolicy;

    @Size(max = 120, message = "Provider name must be at most 120 characters")
    private String providerName;

    @Size(max = 150, message = "Tariff name snapshot must be at most 150 characters")
    private String tariffNameSnapshot;

    private ConnectionType connectionType;

    @Size(max = 1000, message = "Operator restrictions must be at most 1000 characters")
    private String operatorRestrictions;

    private Boolean operatorTermsConfirmed;

    private AccessType accessType;

    @Size(max = 10, message = "Region restriction must be at most 10 characters")
    private String regionRestriction;

    private Boolean requiresEmailForInvite;

    private Boolean emailChangeForbidden;

    @Min(value = 0, message = "Access grant SLA hours cannot be negative")
    private Integer accessGrantSlaHours;
}
