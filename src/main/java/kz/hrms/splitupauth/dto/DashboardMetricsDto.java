package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMetricsDto {
    /** Echoes back the resolved range so clients can label charts unambiguously. */
    private LocalDateTime from;
    private LocalDateTime to;
    private String granularity;
    private List<DashboardMetricPointDto> series;
}
