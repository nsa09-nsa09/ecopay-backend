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
    private String emailMasked;
    private String displayName;
    private String phone;
    private String phoneMasked;
    private Boolean phoneVerified;
    private String avatar;
    private String role;
    private String status;
    private Integer reputation;
    // Risk score is not yet stored on the User entity. We return 0 so the
    // frontend type stays stable; introduce a real column + scorer later.
    private Integer riskScore;
    private Integer roomsOwned;
    private Integer roomsJoined;
    private Integer tickets;
    private Integer disputes;
    private LocalDateTime createdAt;

    public static AdminUserDto from(User u) {
        return AdminUserDto.builder()
                .id(u.getId())
                .email(u.getEmail())
                .emailMasked(maskEmail(u.getEmail()))
                .displayName(u.getDisplayName())
                .phone(u.getPhone())
                .phoneMasked(maskPhone(u.getPhone()))
                .phoneVerified(u.getPhoneVerifiedAt() != null)
                .avatar(u.getAvatar())
                .role(u.getRole() == null ? null : u.getRole().name())
                .status(u.getStatus() == null ? null : u.getStatus().name())
                .reputation(u.getReputation())
                .riskScore(0)
                .roomsOwned(0)
                .roomsJoined(0)
                .tickets(0)
                .disputes(0)
                .createdAt(u.getCreatedAt())
                .build();
    }

    private static String maskEmail(String email) {
        if (email == null || email.isEmpty()) return null;
        int at = email.indexOf('@');
        if (at <= 1) return "***" + (at >= 0 ? email.substring(at) : "");
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.length() < 4) return phone;
        String tail = phone.substring(phone.length() - 4);
        return "***" + tail;
    }
}
