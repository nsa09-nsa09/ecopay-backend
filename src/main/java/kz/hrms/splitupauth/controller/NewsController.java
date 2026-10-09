package kz.hrms.splitupauth.controller;

import kz.hrms.splitupauth.dto.NewsDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.service.NewsImageStorageService;
import kz.hrms.splitupauth.service.NewsService;
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

/**
 * Public surface for the editorial news feed. Mounted under /api/v1/news so it can be whitelisted
 * as permitAll in {@code SecurityConfig}. Returns only PUBLISHED entries — drafts/archives never
 * leak here.
 */
@RestController
@RequestMapping("/api/v1/news")
@RequiredArgsConstructor
public class NewsController {

  private final NewsService newsService;
  private final NewsImageStorageService imageStorage;

  @GetMapping
  public ResponseEntity<PagedResponse<NewsDto>> list(
      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok(newsService.publicList(page, limit));
  }

  @GetMapping("/{id}")
  public ResponseEntity<NewsDto> get(@PathVariable Long id) {
    return ResponseEntity.ok(newsService.publicGet(id));
  }

  /**
   * Streams an attached news image through the backend host (same model as the avatar proxy at
   * /api/v1/users/avatars/{file}), served from the in-process byte cache with a long immutable
   * cache policy and an ETag so repeat loads are 304s.
   */
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

  /** Card-sized preview of a news image; generated on demand for legacy images. */
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
