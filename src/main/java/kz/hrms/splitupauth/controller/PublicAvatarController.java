package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.service.AvatarStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * Public endpoint that streams stored avatars from object storage through the
 * backend host, so clients never see the bucket/endpoint or any credentials.
 * The {@link AvatarStorageService} validates the filename and fetches the bytes
 * from R2; this controller stays tiny so that rule lives in one place.
 */
@RestController
@RequestMapping("/api/v1/users/avatars")
@RequiredArgsConstructor
public class PublicAvatarController {

    private final AvatarStorageService storage;

    @GetMapping("/{filename}")
    public ResponseEntity<byte[]> get(@PathVariable String filename) {
        byte[] data = storage.loadAvatarBytes(filename);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
                .body(data);
    }
}
