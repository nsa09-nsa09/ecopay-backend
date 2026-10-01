package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Compact shape returned from /api/v1/catalog/search — just enough for the navbar dropdown to
 * render each row (icon + name + category) and link to the service page. Heavier fields (provider
 * type, tariff stats) stay on the full {@link ServiceDto} so they don't bloat the type-ahead
 * response.
 *
 * <p>{@code logoUrl} is reserved for the moment when a service-logo upload pipeline lands; for now
 * it is always null but kept in the contract so the frontend can already wire it up.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CatalogSearchResultDto {
  private Long serviceId;
  private String name;
  private String slug;
  private String categoryName;
  private String logoUrl;
}
