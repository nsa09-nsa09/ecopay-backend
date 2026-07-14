package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
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

  // ----- Added in the analytics extension; older clients can ignore them. -----
  /**
   * Unique visitors in the bucket (= COUNT of site_visit rows, since rows are already
   * per-day-deduped).
   */
  private long uniqueVisitors;

  /** Total page views in the bucket (SUM of site_visit.page_count). */
  private long pageViews;

  /** New rooms (non-deleted) created in the bucket. */
  private long newRooms;

  /**
   * Revenue in KZT recognized in the bucket (SUM of successful CHARGE transactions in the bucket
   * window).
   */
  private BigDecimal revenue;

  /**
   * Platform commission revenue in KZT recognized in the bucket (SUM of commission on successful
   * charges).
   */
  private BigDecimal commissionRevenue;
}
