package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.UserDto;
import kz.hrms.splitupauth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class UserMapper {

    private final AvatarStorageService avatarStorageService;

    public UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .publicId(user.getPublicId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .phone(user.getPhone())
                .phoneVerified(user.getPhoneVerifiedAt() != null)
                // Persisted value is an S3 object key; serve it through the backend host.
                .avatar(avatarStorageService.publicUrl(user.getAvatar()))
                .status(user.getStatus())
                .role(user.getRole())
                .reputation(user.getReputation())
                .emailVerified(user.getEmailVerified())
                .build();
    }
}
