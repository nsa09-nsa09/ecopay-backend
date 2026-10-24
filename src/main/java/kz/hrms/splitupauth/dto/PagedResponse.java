package kz.hrms.splitupauth.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PagedResponse<T> {
  private List<T> items;
  private int page;
  private int size;
  private long totalItems;
  private int totalPages;
  private boolean hasNext;
  private boolean hasPrevious;
}
