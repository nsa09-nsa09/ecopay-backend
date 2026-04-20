package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateSupportTicketStatusRequest {

    @NotBlank(message = "Status is required")
    private String status;
}