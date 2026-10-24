package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Row of the /admin/dashboard/operator-distribution chart. {@code code} is the Kazakh 3-digit DEF
 * block of the user's mobile (e.g. "701", "707"), or "OTHER" for foreign / unparseable / missing
 * numbers. {@code operatorName} maps the code to a human-readable carrier name via a small in-code
 * map.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OperatorDistributionDto {
  private String code;
  private String operatorName;
  private long count;
}
