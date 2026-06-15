package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateSiteContentRequest {

    @NotBlank
    @Size(max = 255)
    private String companyName;

    @NotBlank
    @Size(max = 255)
    private String title;

    @Size(max = 4000)
    private String mission;

    @Size(max = 8000)
    private String description;

    @Email
    @Size(max = 255)
    private String contactEmail;

    @Size(max = 64)
    private String contactPhone;
}
