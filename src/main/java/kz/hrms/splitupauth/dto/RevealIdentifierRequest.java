package kz.hrms.splitupauth.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import kz.hrms.splitupauth.entity.IdentifierRevealReasonCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RevealIdentifierRequest {

  @NotNull(message = "Reason code is required")
  private IdentifierRevealReasonCode reasonCode;

  @Size(max = 180, message = "Reason details must be at most 180 characters")
  private String reasonDetails;

  @Size(max = 30, message = "Context type must be at most 30 characters")
  private String contextType;

  private Long contextId;

  /**
   * Source compatibility for older unit tests only. JSON clients must send reasonCode and may send
   * reasonDetails; the service never trusts an arbitrary actor role/event type from this DTO.
   */
  @Deprecated
  @JsonIgnore
  public void setReason(String reason) {
    this.reasonDetails = reason;
    if (this.reasonCode == null) {
      this.reasonCode = IdentifierRevealReasonCode.PROVIDE_SERVICE_ACCESS;
    }
  }

  @Deprecated
  @JsonIgnore
  public String getReason() {
    return reasonDetails;
  }
}
