package kz.hrms.splitupauth.controller;

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
            @RequestParam(required = false) Long categoryId
    ) {
        return ResponseEntity.ok(catalogService.getServices(categoryId));
    }

    @GetMapping("/services/{id}")
    public ResponseEntity<ServiceDto> getService(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getService(id));
    }

    @GetMapping("/services/{id}/tariffs")
    public ResponseEntity<List<TariffPlanDto>> getTariffs(@PathVariable Long id) {
        return ResponseEntity.ok(catalogService.getTariffs(id));
    }
}