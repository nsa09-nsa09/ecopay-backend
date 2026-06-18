package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.CatalogSearchResultDto;
import kz.hrms.splitupauth.dto.CategoryDto;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.dto.TariffPlanDto;
import kz.hrms.splitupauth.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    @GetMapping("/categories")
    public ResponseEntity<List<CategoryDto>> getCategories() {
        return ResponseEntity.ok(catalogService.getCategories());
    }

    @GetMapping("/services")
    public ResponseEntity<List<ServiceDto>> getServices(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false, defaultValue = "name_asc") String sort
    ) {
        return ResponseEntity.ok(catalogService.getServices(categoryId, sort));
    }

    @GetMapping("/services/{id}")
    public ResponseEntity<ServiceDto> getService(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getService(id));
    }

    /**
     * Public navbar search ("Поиск планов…"). Case-insensitive substring match
     * on service name, scoped to active services. Short queries return an empty
     * list silently — see {@link CatalogService#searchServices(String, int)}.
     */
    @GetMapping("/search")
    public ResponseEntity<List<CatalogSearchResultDto>> search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false, defaultValue = "10") int limit
    ) {
        return ResponseEntity.ok(catalogService.searchServices(q, limit));
    }

    @GetMapping("/services/{id}/tariffs")
    public ResponseEntity<List<TariffPlanDto>> getTariffs(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getTariffs(id));
    }
}
