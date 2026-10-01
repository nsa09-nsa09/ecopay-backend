package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Admin PUT body for {@code /api/v1/admin/legal/{docType}}. All fields are optional — absent ones
 * leave the corresponding column unchanged (partial updates). Sized generously to match the TEXT
 * columns.
 */
@Data
public class UpdateLegalDocumentRequest {

  @JsonProperty("title_kz")
  @Size(max = 500)
  private String titleKz;

  @JsonProperty("title_ru")
  @Size(max = 500)
  private String titleRu;

  @JsonProperty("title_en")
  @Size(max = 500)
  private String titleEn;

  @JsonProperty("body_kz")
  @Size(max = 200_000)
  private String bodyKz;

  @JsonProperty("body_ru")
  @Size(max = 200_000)
  private String bodyRu;

  @JsonProperty("body_en")
  @Size(max = 200_000)
  private String bodyEn;
}
