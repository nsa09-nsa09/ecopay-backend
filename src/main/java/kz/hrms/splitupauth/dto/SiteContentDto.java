package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.SiteContent;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SiteContentDto {
    private String companyName;
    private String title;
    private String mission;
    private String description;
    private String contactEmail;
    private String contactPhone;
    private LocalDateTime updatedAt;

    public static SiteContentDto from(SiteContent c) {
        return SiteContentDto.builder()
                .companyName(c.getCompanyName())
                .title(c.getTitle())
                .mission(c.getMission())
                .description(c.getDescription())
                .contactEmail(c.getContactEmail())
                .contactPhone(c.getContactPhone())
                .updatedAt(c.getUpdatedAt())
                .build();
    }
}
