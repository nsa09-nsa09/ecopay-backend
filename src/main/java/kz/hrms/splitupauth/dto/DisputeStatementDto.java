package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.DisputeStatement;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DisputeStatementDto {
  private Long id;
  private Long authorId;
  private String authorDisplayName;
  private String side;
  private String text;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;

  public static DisputeStatementDto from(DisputeStatement s) {
    return DisputeStatementDto.builder()
        .id(s.getId())
        .authorId(s.getAuthor().getId())
        .authorDisplayName(s.getAuthor().getDisplayName())
        .side(s.getSide())
        .text(s.getText())
        .createdAt(s.getCreatedAt())
        .updatedAt(s.getUpdatedAt())
        .build();
  }
}
