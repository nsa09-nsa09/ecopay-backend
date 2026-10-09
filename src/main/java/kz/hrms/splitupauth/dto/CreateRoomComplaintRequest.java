package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A paid member's report that the room owner has not met their obligations. */
@Data
public class CreateRoomComplaintRequest {

  @NotBlank(message = "Complaint reason is required")
  @Size(max = 50, message = "Complaint reason must be at most 50 characters")
  private String reasonCode;

  @NotBlank(message = "Complaint details are required")
  @Size(min = 10, max = 5000, message = "Complaint details must be between 10 and 5000 characters")
  private String description;
}
