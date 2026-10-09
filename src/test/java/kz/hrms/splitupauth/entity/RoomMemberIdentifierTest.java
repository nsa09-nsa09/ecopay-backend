package kz.hrms.splitupauth.entity;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class RoomMemberIdentifierTest {

  @Test
  void toString_doesNotContainEncryptedIdentifier() {
    RoomMemberIdentifier identifier =
        RoomMemberIdentifier.builder()
            .identifierType(IdentifierType.EMAIL)
            .identifierEncrypted("v1:gcm:encrypted-secret")
            .identifierMasked("m****r@example.com")
            .isValidFormat(true)
            .build();

    assertFalse(identifier.toString().contains("encrypted-secret"));
  }
}
