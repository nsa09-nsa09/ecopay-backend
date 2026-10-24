package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.NewsStatus;
import lombok.Data;

/**
 * Admin PUT/PATCH body. Every field is optional; the service only writes the columns that are
 * non-null in the request (partial update), so a single field edit doesn't blank out languages the
 * admin didn't touch.
 */
@Data
public class UpdateNewsRequest {

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

  private NewsStatus status;

  @PositiveOrZero private Integer sortOrder;
}
