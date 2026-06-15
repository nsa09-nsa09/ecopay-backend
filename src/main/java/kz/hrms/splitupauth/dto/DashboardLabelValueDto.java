package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Generic {label, value} row for the admin dashboard's distribution charts
 * (currency / category / room-status / operator). Used wherever the chart only
 * needs a name + a count, so the FE doesn't have to special-case each shape.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardLabelValueDto {
    private String label;
    private long value;
}
