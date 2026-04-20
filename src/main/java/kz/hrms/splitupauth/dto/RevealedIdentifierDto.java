package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class RevealedIdentifierDto {
    private Long roomId;
    private Long roomMemberId;
    private String identifierType;
    private String identifierValue;
    private String revealedForReason;
}