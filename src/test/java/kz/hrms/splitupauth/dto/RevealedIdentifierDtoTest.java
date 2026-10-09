package kz.hrms.splitupauth.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class RevealedIdentifierDtoTest {

  @Test
  void toString_doesNotContainPlaintextIdentifier() {
    RevealedIdentifierDto dto =
        RevealedIdentifierDto.builder()
            .roomId(1L)
            .roomMemberId(2L)
            .identifierType("PHONE")
            .identifierValue("+77051234567")
            .revealTtlSeconds(30L)
            .build();

    assertFalse(dto.toString().contains("+77051234567"));
  }
}
