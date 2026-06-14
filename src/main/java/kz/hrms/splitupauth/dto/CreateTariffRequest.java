package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.PeriodType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTariffRequest {

    @NotBlank
    @Size(max = 150)
    private String name;

    @NotNull
    private PeriodType periodType;

    @NotNull
    @Min(value = 2, message = "maxMembers must be at least 2")
    private Integer maxMembers;

    @NotNull
    @DecimalMin(value = "0.01", message = "basePriceTotal must be > 0")
    private BigDecimal basePriceTotal;

    @Size(max = 10)
    private String currency;

    private ConnectionType connectionType;

    private String operatorRules;

    private List<String> features;
}
