package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.PeriodType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UpdateTariffRequest {

    @Size(max = 150)
    private String name;

    private PeriodType periodType;

    @Min(value = 2, message = "maxMembers must be at least 2")
    private Integer maxMembers;

    @DecimalMin(value = "0.01", message = "basePriceTotal must be > 0")
    private BigDecimal basePriceTotal;

    @Size(max = 10)
    private String currency;

    private ConnectionType connectionType;

    private String operatorRules;

    private Boolean isActive;
}
