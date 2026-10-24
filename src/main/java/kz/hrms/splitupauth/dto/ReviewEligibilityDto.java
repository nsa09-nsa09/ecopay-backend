package kz.hrms.splitupauth.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * B5: answers "can the caller leave a review for this recipient, and if so, in which rooms?". The
 * rooms list is empty when {@code eligible} is false.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewEligibilityDto {
  private boolean eligible;
  private List<EligibleRoom> rooms;

  @Data
  @Builder
  @NoArgsConstructor
  @AllArgsConstructor
  public static class EligibleRoom {
    private Long roomId;
    private String roomTitle;
  }
}
