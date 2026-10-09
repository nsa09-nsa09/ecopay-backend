package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.StoryDto;
import kz.hrms.splitupauth.service.StoryImageStorageService;
import kz.hrms.splitupauth.service.StoryService;
import kz.hrms.splitupauth.util.CachedImageHttp;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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
  public ResponseEntity<byte[]> getImage(
      @PathVariable String filename,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    return CachedImageHttp.serve(
        filename,
        imageStorage.servable(filename),
        ifNoneMatch,
        () -> imageStorage.loadImageBytes(filename));
  }

  /** Card-sized preview of a story image; generated on demand for legacy images. */
  @GetMapping("/images/thumb/{filename}")
  public ResponseEntity<byte[]> getThumb(
      @PathVariable String filename,
      @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
    return CachedImageHttp.serve(
        filename,
        imageStorage.servable(filename),
        ifNoneMatch,
        () -> imageStorage.loadThumbBytes(filename));
  }
}
