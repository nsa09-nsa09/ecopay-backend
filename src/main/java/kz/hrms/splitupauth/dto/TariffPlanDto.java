package kz.hrms.splitupauth.dto;

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
public class TariffPlanDto {
    private Long id;
    private Long serviceId;
    private String name;
    private PeriodType periodType;
    private Integer maxMembers;
    private BigDecimal basePriceTotal;
    private String currency;
    private ConnectionType connectionType;
    private String operatorRules;
}