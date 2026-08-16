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
public class PublicHomeStatsDto {
  private long totalUsers;
  private long completedOrActiveMemberships;
  private long verifiedReviewCount;
  private BigDecimal averageVerifiedRating;
  private long activeRooms;
}
