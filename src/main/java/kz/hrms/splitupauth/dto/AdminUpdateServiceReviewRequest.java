package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminUpdateServiceReviewRequest {

    @Min(1)
    @Max(5)
    private Integer rating;

    @Size(max = 2000)
    private String text;
}
