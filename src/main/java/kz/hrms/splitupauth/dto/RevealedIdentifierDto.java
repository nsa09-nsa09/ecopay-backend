package kz.hrms.splitupauth.dto;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

@Getter
@Builder
@ToString(exclude = "identifierValue")
public class RevealedIdentifierDto {
  private Long roomId;
  private Long roomMemberId;
  private String identifierType;
  private String identifierValue;
  private Long revealTtlSeconds;
}
