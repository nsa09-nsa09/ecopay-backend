package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DashboardMetricPointDto {
    /** ISO label of the bucket — "yyyy-MM" for month, "yyyy-MM-dd" for day. */
    private String period;
    private long registrations;
    /** Total successful logins in the bucket (counts a user once per attempt). */
    private long loginsTotal;
    /** Distinct accounts that successfully logged in within the bucket. */
    private long uniqueLogins;
}
