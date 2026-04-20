package kz.hrms.splitupauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Page;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PageResponse<T> {
    private int page;
    private int size;
    private long totalItems;
    private int totalPages;
    private List<T> items;

    public static <T, R> PageResponse<R> from(Page<T> source, List<R> mappedItems) {
        return PageResponse.<R>builder()
                .page(source.getNumber())
                .size(source.getSize())
                .totalItems(source.getTotalElements())
                .totalPages(source.getTotalPages())
                .items(mappedItems)
                .build();
    }
}