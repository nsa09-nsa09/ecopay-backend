package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class BatchConfirmRequest {

    @NotEmpty(message = "Queue ids are required")
    private List<Long> queueIds;

    @NotBlank(message = "Reason is required")
    private String reason;
}