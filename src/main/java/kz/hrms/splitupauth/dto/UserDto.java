package kz.hrms.splitupauth.dto;

import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDto {
    private Long id;
    private String email;
    private String displayName;
    private String avatar;
    private UserStatus status;
    private Role role;
    private Integer reputation;
}