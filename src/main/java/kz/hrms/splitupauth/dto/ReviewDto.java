package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.Review;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ReviewDto {
    private Long id;
    private Long authorId;
    private String authorDisplayName;
    private Long recipientId;
    private Long roomId;
    private Integer rating;
    private String text;
    private LocalDateTime createdAt;

    public static ReviewDto from(Review r) {
        return ReviewDto.builder()
                .id(r.getId())
                .authorId(r.getAuthor() == null ? null : r.getAuthor().getId())
                .authorDisplayName(r.getAuthor() == null ? null : r.getAuthor().getDisplayName())
                .recipientId(r.getRecipient() == null ? null : r.getRecipient().getId())
                .roomId(r.getRoom() == null ? null : r.getRoom().getId())
                .rating(r.getRating())
                .text(r.getText())
                .createdAt(r.getCreatedAt())
                .build();
    }
}
