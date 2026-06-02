package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuthResponse {
    private String accessToken;
    private String refreshToken;
    private UserDto user;

    // ADMIN / SUPPORT 2FA challenge fields. Populated only when the first login
    // step succeeds for a staff account and a second step is required.
    private Boolean requiresTwoFactor;
    private String challengeId;
    private LocalDateTime expiresAt;
    private String maskedEmail;
}
