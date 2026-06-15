package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin PUT body. The legacy {title,mission,description} stay required so old
 * admin UIs that haven't been updated for tri-lingual editing keep working;
 * the per-language fields are optional add-ons. The service mirrors {legacy →
 * *_ru} on save when only the legacy fields are supplied, so RU stays the
 * canonical default locale.
 */
@Data
public class UpdateSiteContentRequest {

    @NotBlank
    @Size(max = 255)
    private String companyName;

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 4000)
    private String mission;

    @Size(max = 8000)
    private String description;

    @Email
    @Size(max = 255)
    private String contactEmail;

    @Size(max = 64)
    private String contactPhone;

    // ----- Tri-lingual fields (V30). All optional; absent ones leave the
    // corresponding column unchanged. Sized generously to match TEXT columns
    // but capped to discourage abuse. -----

    @Size(max = 255)
    private String titleKz;

    @Size(max = 255)
    private String titleRu;

    @Size(max = 255)
    private String titleEn;

    @Size(max = 4000)
    private String missionKz;

    @Size(max = 4000)
    private String missionRu;

    @Size(max = 4000)
    private String missionEn;

    @Size(max = 8000)
    private String descriptionKz;

    @Size(max = 8000)
    private String descriptionRu;

    @Size(max = 8000)
    private String descriptionEn;
}
