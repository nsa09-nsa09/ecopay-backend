package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReputationDto {
    private Long userId;
    private String displayName;
    private Integer reputation;
    private Double averageRating;
    private Long reviewsCount;
    private Long completedRoomsCount;
}
