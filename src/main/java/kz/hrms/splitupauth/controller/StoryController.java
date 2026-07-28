package kz.hrms.splitupauth.controller;

import java.time.Duration;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.StoryDto;
import kz.hrms.splitupauth.service.StoryImageStorageService;
import kz.hrms.splitupauth.service.StoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/stories")
@RequiredArgsConstructor
public class StoryController {

  private final StoryService storyService;
  private final StoryImageStorageService imageStorage;

  @GetMapping
  public ResponseEntity<PagedResponse<StoryDto>> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok(storyService.publicList(page, limit));
  }

  @GetMapping("/images/{filename}")
  public ResponseEntity<byte[]> getImage(@PathVariable String filename) {
    byte[] data = imageStorage.loadImageBytes(filename);
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic())
        .body(data);
  }
}
