package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.service.AvatarStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Public endpoint that streams stored avatar files. The {@link AvatarStorageService}
 * is what actually validates the filename against the avatars directory — this
 * controller is intentionally tiny so the rule lives in one place.
 */
@RestController
@RequestMapping("/api/v1/users/avatars")
@RequiredArgsConstructor
public class PublicAvatarController {

    private final AvatarStorageService storage;

    @GetMapping("/{filename}")
    public ResponseEntity<Resource> get(@PathVariable String filename) {
        Path path = storage.resolve(filename)
                .orElseThrow(() -> new ResourceNotFoundException("Avatar not found"));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(new FileSystemResource(path));
    }
}
