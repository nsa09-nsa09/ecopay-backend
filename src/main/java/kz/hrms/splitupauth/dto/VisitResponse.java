package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VisitResponse {
    private UUID visitorId;
    /** True if this hit was the first record for the visitor today (unique visitor of the day). */
    private boolean newVisitorToday;
}
