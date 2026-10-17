package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** C4: admin reason for ending an overdue membership. Required for the audit log. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EndOverdueMemberRequest {

  @NotBlank
  @Size(max = 500)
  private String reason;
}
