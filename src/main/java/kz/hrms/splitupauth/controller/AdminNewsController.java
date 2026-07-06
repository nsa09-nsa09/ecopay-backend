package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateNewsRequest;
import kz.hrms.splitupauth.dto.NewsDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.UpdateNewsRequest;
import kz.hrms.splitupauth.entity.NewsStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.NewsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * ADMIN-only CRUD for news. All routes also fall under the {@code /api/v1/admin/**} matcher in
 * {@code SecurityConfig}; the explicit {@code @PreAuthorize} keeps the rule local to this
 * controller for readability.
 */
@RestController
@RequestMapping("/api/v1/admin/news")
@RequiredArgsConstructor
public class AdminNewsController {

  private final NewsService newsService;

  @GetMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PagedResponse<NewsDto>> list(
      @RequestParam(required = false) NewsStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(newsService.adminList(status, page, size));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<NewsDto> get(@PathVariable Long id) {
    return ResponseEntity.ok(newsService.adminGet(id));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<NewsDto> create(
      @AuthenticationPrincipal User admin,
      @Valid @RequestBody CreateNewsRequest request,
      HttpServletRequest http) {
    return ResponseEntity.status(HttpStatus.CREATED).body(newsService.create(admin, request, http));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<NewsDto> update(
      @PathVariable Long id,
      @AuthenticationPrincipal User admin,
      @Valid @RequestBody UpdateNewsRequest request,
      HttpServletRequest http) {
    return ResponseEntity.ok(newsService.update(id, admin, request, http));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<NewsDto> patch(
      @PathVariable Long id,
      @AuthenticationPrincipal User admin,
      @Valid @RequestBody UpdateNewsRequest request,
      HttpServletRequest http) {
    return ResponseEntity.ok(newsService.update(id, admin, request, http));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<Void> delete(
      @PathVariable Long id, @AuthenticationPrincipal User admin, HttpServletRequest http) {
    newsService.delete(id, admin, http);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/image")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<NewsDto> uploadImage(
      @PathVariable Long id,
      @AuthenticationPrincipal User admin,
      @RequestPart("file") MultipartFile file,
      HttpServletRequest http) {
    return ResponseEntity.ok(newsService.uploadImage(id, admin, file, http));
  }
}
