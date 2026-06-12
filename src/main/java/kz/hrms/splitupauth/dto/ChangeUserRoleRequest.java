package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.Role;
import lombok.Data;

@Data
public class ChangeUserRoleRequest {

    @NotNull(message = "Role is required")
    private Role role;

    @NotBlank(message = "Reason is required")
    @Size(max = 500, message = "Reason must be at most 500 characters")
    private String reason;
}
