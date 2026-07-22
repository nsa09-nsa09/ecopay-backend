package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.EmailChangeConfirmRequest;
import kz.hrms.splitupauth.dto.EmailChangeRequest;
import kz.hrms.splitupauth.dto.MemberDashboardDto;
import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.dto.SlugAvailabilityDto;
import kz.hrms.splitupauth.dto.UpdateProfileRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.EmailChangeService;
import kz.hrms.splitupauth.service.MailLocale;
import kz.hrms.splitupauth.service.MemberDashboardService;
import kz.hrms.splitupauth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;
  private final MemberDashboardService memberDashboardService;
  private final EmailChangeService emailChangeService;

  @GetMapping("/me")
  public ResponseEntity<UserDto> getCurrentUser(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(userService.getCurrentUser(user));
  }

  @PatchMapping("/me")
  public ResponseEntity<UserDto> updateProfile(
      @AuthenticationPrincipal User user, @Valid @RequestBody UpdateProfileRequest request) {
    return ResponseEntity.ok(userService.updateProfile(user, request));
  }

  @DeleteMapping("/me")
  public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal User user) {
    userService.deleteAccount(user);
    return ResponseEntity.noContent().build();
  }

  /**
   * Add or change the account email, step 1: emails a one-time confirmation code to the new
   * address. The account keeps its current email (or none) until the code is confirmed.
   */
  @PostMapping("/me/email/request")
  public ResponseEntity<Void> requestEmailChange(
      @AuthenticationPrincipal User user,
      @Valid @RequestBody EmailChangeRequest request,
      @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage) {
    // The web client sends its in-app language here, so the confirmation email
    // arrives in the language the user is actually reading the site in rather
    // than whatever their browser was installed with.
    emailChangeService.requestChange(user, request.getEmail(), MailLocale.from(acceptLanguage));
    return ResponseEntity.accepted().build();
  }

  /** Add or change the account email, step 2: confirm the emailed 6-digit code. */
  @PostMapping("/me/email/confirm")
  public ResponseEntity<UserDto> confirmEmailChange(
      @AuthenticationPrincipal User user, @Valid @RequestBody EmailChangeConfirmRequest request) {
    return ResponseEntity.ok(emailChangeService.confirmChange(user, request.getCode()));
  }

  @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<UserDto> uploadAvatar(
      @AuthenticationPrincipal User user, @RequestParam("file") MultipartFile file) {
    return ResponseEntity.ok(userService.uploadAvatar(user, file));
  }

  @DeleteMapping("/me/avatar")
  public ResponseEntity<UserDto> deleteAvatar(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(userService.deleteAvatar(user));
  }

  @GetMapping("/public/{handle}")
  public ResponseEntity<PublicProfileDto> getPublicProfile(@PathVariable String handle) {
    return ResponseEntity.ok(userService.getPublicProfile(handle));
  }

  /**
   * Live availability probe used by the profile editor: normalizes the requested slug and reports
   * whether the caller could claim it. Requires auth so unauthenticated callers can't scrape the
   * slug namespace.
   */
  @GetMapping("/me/slug-available")
  public ResponseEntity<SlugAvailabilityDto> checkSlugAvailability(
      @AuthenticationPrincipal User user, @RequestParam("slug") String slug) {
    return ResponseEntity.ok(userService.checkSlugAvailability(user, slug));
  }

  /** Personal analytics surface: memberships, spend, savings, reputation, recent activity. */
  @GetMapping("/me/dashboard")
  @PreAuthorize("isAuthenticated()")
  public ResponseEntity<MemberDashboardDto> getMyDashboard(@AuthenticationPrincipal User user) {
    return ResponseEntity.ok(memberDashboardService.getMyDashboard(user));
  }
}
