package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.CatalogSearchResultDto;
import kz.hrms.splitupauth.dto.CategoryDto;
import kz.hrms.splitupauth.dto.RoomMatchDto;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.dto.TariffPlanDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.CatalogService;
import kz.hrms.splitupauth.service.ServiceLogoStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;
    private final ServiceLogoStorageService logoStorage;

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

    /**
     * FIFO match for the subscription flow: tells the FE whether to push the
     * user into an existing room (oldest OPEN with a free seat the user
     * doesn't own) or to start a new one. Auth required so we can exclude the
     * caller's own rooms from the match — anonymous "should I join?" doesn't
     * make sense.
     */
    @GetMapping("/services/{id}/match")
    public ResponseEntity<RoomMatchDto> matchRoom(
            @PathVariable Long id,
            @AuthenticationPrincipal User currentUser
    ) {
        return ResponseEntity.ok(catalogService.matchRoomForService(id, currentUser));
    }

    /**
     * Public service-logo proxy. Mirrors the avatar / news-image streamers:
     * the storage service validates the filename so it can only resolve to a
     * key under the {@code service-logos/} prefix.
     */
    @GetMapping("/services/logos/{filename}")
    public ResponseEntity<byte[]> getServiceLogo(@PathVariable String filename) {
        byte[] data = logoStorage.loadImageBytes(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(data);
    }
}
