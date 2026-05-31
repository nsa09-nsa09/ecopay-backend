package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.SavedCard;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SavedCardDto {
    private Long id;
    private String providerName;
    private String panMask;
    private String cardType;
    private Integer expiryMonth;
    private Integer expiryYear;
    private Boolean isDefault;
    private String status;
    private LocalDateTime createdAt;

    public static SavedCardDto from(SavedCard c) {
        return SavedCardDto.builder()
                .id(c.getId())
                .providerName(c.getProviderName())
                .panMask(c.getPanMask())
                .cardType(c.getCardType())
                .expiryMonth(c.getExpiryMonth())
                .expiryYear(c.getExpiryYear())
                .isDefault(c.getIsDefault())
                .status(c.getStatus() == null ? null : c.getStatus().name())
                .createdAt(c.getCreatedAt())
                .build();
    }
}
