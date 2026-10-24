package kz.hrms.splitupauth.dto;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitResponse {
  private UUID visitorId;

  /** True if this hit was the first record for the visitor today (unique visitor of the day). */
  private boolean newVisitorToday;
}
