package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.ConnectionType;
import kz.hrms.splitupauth.entity.PeriodType;
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
public class AdminTariffDto {
    private Long id;
    private Long serviceId;
    private String name;
    private PeriodType periodType;
    private Integer maxMembers;
    private BigDecimal basePriceTotal;
    private String currency;
    private ConnectionType connectionType;
    private String operatorRules;
    private Boolean isActive;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
