package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.AdminCategoryDto;
import kz.hrms.splitupauth.dto.AdminServiceDto;
import kz.hrms.splitupauth.dto.AdminTariffDto;
import kz.hrms.splitupauth.dto.CreateCategoryRequest;
import kz.hrms.splitupauth.dto.CreateServiceRequest;
import kz.hrms.splitupauth.dto.CreateTariffRequest;
import kz.hrms.splitupauth.dto.UpdateCategoryRequest;
import kz.hrms.splitupauth.dto.UpdateServiceRequest;
import kz.hrms.splitupauth.dto.UpdateTariffRequest;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.AdminCatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/catalog")
@RequiredArgsConstructor
public class AdminCatalogController {

    private final AdminCatalogService adminCatalogService;

    // ===================== Categories =====================

    @GetMapping("/categories")
    public ResponseEntity<List<AdminCategoryDto>> listCategories() {
        return ResponseEntity.ok(adminCatalogService.listCategories());
    }

    @PostMapping("/categories")
    public ResponseEntity<AdminCategoryDto> createCategory(
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody CreateCategoryRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminCatalogService.createCategory(admin, req, http));
    }

    @PutMapping("/categories/{id}")
    public ResponseEntity<AdminCategoryDto> updateCategory(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody UpdateCategoryRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.ok(adminCatalogService.updateCategory(id, admin, req, http));
    }

    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> deleteCategory(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            HttpServletRequest http
    ) {
        adminCatalogService.deleteCategory(id, admin, http);
        return ResponseEntity.ok().build();
    }

    // ===================== Services =====================

    @GetMapping("/services")
    public ResponseEntity<List<AdminServiceDto>> listServices(
            @RequestParam(required = false) Long categoryId
    ) {
        return ResponseEntity.ok(adminCatalogService.listServices(categoryId));
    }

    @PostMapping("/services")
    public ResponseEntity<AdminServiceDto> createService(
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody CreateServiceRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminCatalogService.createService(admin, req, http));
    }

    @PutMapping("/services/{id}")
    public ResponseEntity<AdminServiceDto> updateService(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody UpdateServiceRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.ok(adminCatalogService.updateService(id, admin, req, http));
    }

    @DeleteMapping("/services/{id}")
    public ResponseEntity<Void> deleteService(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            HttpServletRequest http
    ) {
        adminCatalogService.deleteService(id, admin, http);
        return ResponseEntity.ok().build();
    }

    // ===================== Tariffs =====================

    @GetMapping("/services/{serviceId}/tariffs")
    public ResponseEntity<List<AdminTariffDto>> listTariffs(@PathVariable Long serviceId) {
        return ResponseEntity.ok(adminCatalogService.listTariffs(serviceId));
    }

    @PostMapping("/services/{serviceId}/tariffs")
    public ResponseEntity<AdminTariffDto> createTariff(
            @PathVariable Long serviceId,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody CreateTariffRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminCatalogService.createTariff(serviceId, admin, req, http));
    }

    @PutMapping("/tariffs/{id}")
    public ResponseEntity<AdminTariffDto> updateTariff(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            @Valid @RequestBody UpdateTariffRequest req,
            HttpServletRequest http
    ) {
        return ResponseEntity.ok(adminCatalogService.updateTariff(id, admin, req, http));
    }

    @DeleteMapping("/tariffs/{id}")
    public ResponseEntity<Void> deleteTariff(
            @PathVariable Long id,
            @AuthenticationPrincipal User admin,
            HttpServletRequest http
    ) {
        adminCatalogService.deleteTariff(id, admin, http);
        return ResponseEntity.ok().build();
    }
}
