package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import kz.hrms.splitupauth.entity.LegalDocument;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class LegalDocumentDto {

    /** Lower-case string ("terms" | "privacy") on the wire. */
    private String docType;

    private Integer version;
    private LocalDateTime updatedAt;

    @JsonProperty("title_kz") private String titleKz;
    @JsonProperty("title_ru") private String titleRu;
    @JsonProperty("title_en") private String titleEn;

    @JsonProperty("body_kz") private String bodyKz;
    @JsonProperty("body_ru") private String bodyRu;
    @JsonProperty("body_en") private String bodyEn;

    public static LegalDocumentDto from(LegalDocument d) {
        return LegalDocumentDto.builder()
                .docType(d.getDocType() != null
                        ? d.getDocType().name().toLowerCase()
                        : null)
                .version(d.getVersion())
                .updatedAt(d.getUpdatedAt())
                .titleKz(d.getTitleKz())
                .titleRu(d.getTitleRu())
                .titleEn(d.getTitleEn())
                .bodyKz(d.getBodyKz())
                .bodyRu(d.getBodyRu())
                .bodyEn(d.getBodyEn())
                .build();
    }
}
