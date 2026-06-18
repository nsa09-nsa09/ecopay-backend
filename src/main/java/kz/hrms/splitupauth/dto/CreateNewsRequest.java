package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.NewsStatus;
import lombok.Data;

/**
 * Admin POST body for creating a news item. Every language field is optional
 * so a draft can be saved with only one locale filled in; the service rejects
 * a row with no title at all in any language. Length caps match the
 * UpdateSiteContentRequest style so abuse stays bounded.
 */
@Data
public class CreateNewsRequest {

    @Size(max = 255)
    private String titleKz;

    @Size(max = 255)
    private String titleRu;

    @Size(max = 255)
    private String titleEn;

    @Size(max = 20000)
    private String bodyKz;

    @Size(max = 20000)
    private String bodyRu;

    @Size(max = 20000)
    private String bodyEn;

    /** Defaults to DRAFT when omitted. */
    private NewsStatus status;

    @PositiveOrZero
    private Integer sortOrder;
}
