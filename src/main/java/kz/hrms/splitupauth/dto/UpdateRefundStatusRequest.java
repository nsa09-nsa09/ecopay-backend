package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateRefundStatusRequest {

    @Size(max = 150, message = "Provider refund id must be at most 150 characters")
    private String providerRefundId;
}