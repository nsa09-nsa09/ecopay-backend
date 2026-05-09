package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.User;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminUserDto {
    private Long id;
    private String email;
    private String displayName;
    private String phone;
    private Boolean phoneVerified;
    private String role;
    private String status;
    private Integer reputation;
    private LocalDateTime createdAt;

    public static AdminUserDto from(User u) {
        return AdminUserDto.builder()
                .id(u.getId())
                .email(u.getEmail())
                .displayName(u.getDisplayName())
                .phone(u.getPhone())
                .phoneVerified(u.getPhoneVerifiedAt() != null)
                .role(u.getRole() == null ? null : u.getRole().name())
                .status(u.getStatus() == null ? null : u.getStatus().name())
                .reputation(u.getReputation())
                .createdAt(u.getCreatedAt())
                .build();
    }
}
