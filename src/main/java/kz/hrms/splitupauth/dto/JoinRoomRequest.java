package kz.hrms.splitupauth.dto;

import jakarta.validation.constraints.NotNull;
import kz.hrms.splitupauth.entity.IdentifierType;
import lombok.Data;

@Data
public class JoinRoomRequest {

    @NotNull(message = "Consent must be accepted")
    private Boolean consentAccepted;

    private IdentifierType identifierType;

    private String identifierValue;
}