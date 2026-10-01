package kz.hrms.splitupauth.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MemberHoldDto {
  private BigDecimal heldAmount;
  private String currency;
  private long heldPayoutCount;
  private LocalDateTime nextReleaseAt;
  private Long beneficiaryUserId;
  private String beneficiaryDisplayName;
  private String beneficiaryPublicId;
  private String beneficiarySlug;
}
