package kz.hrms.splitupauth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.CreateStoryRequest;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.StoryDto;
import kz.hrms.splitupauth.dto.UpdateStoryRequest;
import kz.hrms.splitupauth.entity.StoryStatus;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.StoryService;
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

@RestController
@RequestMapping("/api/v1/admin/stories")
@RequiredArgsConstructor
public class AdminStoryController {

  private final StoryService storyService;

  @GetMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<PagedResponse<StoryDto>> list(
      @RequestParam(required = false) StoryStatus status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(storyService.adminList(status, page, size));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<StoryDto> get(@PathVariable Long id) {
    return ResponseEntity.ok(storyService.adminGet(id));
  }

  @PostMapping
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<StoryDto> create(
      @AuthenticationPrincipal User admin,
      @Valid @RequestBody CreateStoryRequest request,
      HttpServletRequest http) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(storyService.create(admin, request, http));
  }

  @PutMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<StoryDto> update(
      @PathVariable Long id,
      @AuthenticationPrincipal User admin,
      @Valid @RequestBody UpdateStoryRequest request,
      HttpServletRequest http) {
    return ResponseEntity.ok(storyService.update(id, admin, request, http));
  }

  @PatchMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<StoryDto> patch(
      @PathVariable Long id,
      @AuthenticationPrincipal User admin,
      @Valid @RequestBody UpdateStoryRequest request,
      HttpServletRequest http) {
    return ResponseEntity.ok(storyService.update(id, admin, request, http));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<Void> delete(
      @PathVariable Long id, @AuthenticationPrincipal User admin, HttpServletRequest http) {
    storyService.delete(id, admin, http);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/image")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<StoryDto> uploadImage(
      @PathVariable Long id,
      @AuthenticationPrincipal User admin,
      @RequestPart("file") MultipartFile file,
      HttpServletRequest http) {
    return ResponseEntity.ok(storyService.uploadImage(id, admin, file, http));
  }

  @DeleteMapping("/{id}/image")
  @PreAuthorize("hasAuthority('ADMIN')")
  public ResponseEntity<StoryDto> deleteImage(
      @PathVariable Long id, @AuthenticationPrincipal User admin, HttpServletRequest http) {
    return ResponseEntity.ok(storyService.deleteImage(id, admin, http));
  }
}
