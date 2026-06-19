package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import kz.hrms.splitupauth.entity.SiteContent;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Locale;

@Data
@Builder
public class SiteContentDto {
    private String companyName;
    /** Legacy single-locale fields — kept for backward compatibility. Mirror *_ru. */
    private String title;
    private String mission;
    private String description;

    private String contactEmail;
    private String contactPhone;
    private LocalDateTime updatedAt;

    // ----- Tri-lingual variants (V30) -----
    // Wire names are snake_case to match the admin About editor contract.
    @JsonProperty("title_kz")       private String titleKz;
    @JsonProperty("title_ru")       private String titleRu;
    @JsonProperty("title_en")       private String titleEn;

    @JsonProperty("mission_kz")     private String missionKz;
    @JsonProperty("mission_ru")     private String missionRu;
    @JsonProperty("mission_en")     private String missionEn;

    @JsonProperty("description_kz") private String descriptionKz;
    @JsonProperty("description_ru") private String descriptionRu;
    @JsonProperty("description_en") private String descriptionEn;

    public static SiteContentDto from(SiteContent c) {
        return SiteContentDto.builder()
                .companyName(c.getCompanyName())
                .title(c.getTitle())
                .mission(c.getMission())
                .description(c.getDescription())
                .contactEmail(c.getContactEmail())
                .contactPhone(c.getContactPhone())
                .updatedAt(c.getUpdatedAt())
                .titleKz(c.getTitleKz())
                .titleRu(c.getTitleRu())
                .titleEn(c.getTitleEn())
                .missionKz(c.getMissionKz())
                .missionRu(c.getMissionRu())
                .missionEn(c.getMissionEn())
                .descriptionKz(c.getDescriptionKz())
                .descriptionRu(c.getDescriptionRu())
                .descriptionEn(c.getDescriptionEn())
                .build();
    }

    /**
     * Convenience for clients that don't want to pick fields themselves.
     * Collapses the chosen language back into the legacy {title,mission,description}
     * slots while keeping the per-language fields available.
     */
    public SiteContentDto preferLanguage(String lang) {
        if (lang == null) return this;
        String normalised = lang.trim().toLowerCase(Locale.ROOT);
        switch (normalised) {
            case "kz", "kk" -> {
                if (titleKz != null)       this.title = titleKz;
                if (missionKz != null)     this.mission = missionKz;
                if (descriptionKz != null) this.description = descriptionKz;
            }
            case "en" -> {
                if (titleEn != null)       this.title = titleEn;
                if (missionEn != null)     this.mission = missionEn;
                if (descriptionEn != null) this.description = descriptionEn;
            }
            case "ru" -> {
                if (titleRu != null)       this.title = titleRu;
                if (missionRu != null)     this.mission = missionRu;
                if (descriptionRu != null) this.description = descriptionRu;
            }
            default -> { /* unknown locale — leave the legacy fields untouched */ }
        }
        return this;
    }
}
