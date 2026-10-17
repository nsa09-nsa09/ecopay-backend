package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class SupportMessageDto {
  private Long id;
  private Long senderUserId;
  private String senderRole;
  private String message;
  private String attachmentUrl;
  private LocalDateTime createdAt;
}
