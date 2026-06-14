package kz.hrms.splitupauth.controller;

import jakarta.validation.Valid;
import kz.hrms.splitupauth.dto.PublicProfileDto;
import kz.hrms.splitupauth.dto.UpdateProfileRequest;
import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserDto> getCurrentUser(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.getCurrentUser(user));
    }

    @PatchMapping("/me")
    public ResponseEntity<UserDto> updateProfile(
            @AuthenticationPrincipal User user,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ResponseEntity.ok(userService.updateProfile(user, request));
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteAccount(@AuthenticationPrincipal User user) {
        userService.deleteAccount(user);
        return ResponseEntity.noContent().build();
    }

    @PostMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<UserDto> uploadAvatar(
            @AuthenticationPrincipal User user,
            @RequestParam("file") MultipartFile file
    ) {
        return ResponseEntity.ok(userService.uploadAvatar(user, file));
    }

    @DeleteMapping("/me/avatar")
    public ResponseEntity<UserDto> deleteAvatar(@AuthenticationPrincipal User user) {
        return ResponseEntity.ok(userService.deleteAvatar(user));
    }

    @GetMapping("/public/{publicId}")
    public ResponseEntity<PublicProfileDto> getPublicProfile(@PathVariable String publicId) {
        return ResponseEntity.ok(userService.getPublicProfile(publicId));
    }
}
