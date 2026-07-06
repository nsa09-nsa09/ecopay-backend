package kz.hrms.splitupauth.dto;

import java.time.LocalDateTime;
import kz.hrms.splitupauth.entity.PayoutMethod;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PayoutMethodDto {
  private Long id;
  private String providerName;
  private String panMask;
  private Boolean isDefault;
  private String status;
  private LocalDateTime createdAt;

  public static PayoutMethodDto from(PayoutMethod m) {
    return PayoutMethodDto.builder()
        .id(m.getId())
        .providerName(m.getProviderName())
        .panMask(m.getPanMask())
        .isDefault(m.getIsDefault())
        .status(m.getStatus())
        .createdAt(m.getCreatedAt())
        .build();
  }
}
