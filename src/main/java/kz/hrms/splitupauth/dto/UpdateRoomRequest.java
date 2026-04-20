package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.ConnectionType;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class UpdateRoomRequest {

    @Size(max = 150, message = "Title must be at most 150 characters")
    private String title;

    @Size(max = 2000, message = "Description must be at most 2000 characters")
    private String description;

    @Min(value = 2, message = "Max members must be at least 2")
    private Integer maxMembers;

    private BigDecimal priceTotal;

    private BigDecimal pricePerMember;

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
}
