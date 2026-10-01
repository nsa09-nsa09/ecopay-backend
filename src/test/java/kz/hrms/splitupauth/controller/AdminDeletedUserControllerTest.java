package kz.hrms.splitupauth.controller;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import java.util.List;
import java.util.Optional;
import kz.hrms.splitupauth.dto.DeletedIdentifiersRevealRequest;
import kz.hrms.splitupauth.entity.AdminActionLog;
import kz.hrms.splitupauth.entity.AdminActionType;
import kz.hrms.splitupauth.entity.DeletedUserIdentityArchive;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DeletedUserIdentityArchiveRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.security.FieldEncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class AdminDeletedUserControllerTest {
  @Mock UserRepository users;
  @Mock DeletedUserIdentityArchiveRepository archives;
  @Mock FieldEncryptionService encryption;
  @Mock AdminActionLogRepository audit;
  @Mock HttpServletRequest request;

  @Test
  void listIsMaskedAndLegacyDeletedUserHasNoInventedIdentity() {
    User archivedUser =
        User.builder().id(1L).publicId("archived").status(UserStatus.DELETED).build();
    User legacyUser = User.builder().id(2L).publicId("legacy").status(UserStatus.DELETED).build();
    DeletedUserIdentityArchive archive = new DeletedUserIdentityArchive();
    archive.setUser(archivedUser);
    archive.setEmailEncrypted("ciphertext");
    archive.setEmailMasked("a***@gmail.com");
    when(users.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(archivedUser, legacyUser)));
    when(archives.findByUser_IdIn(any())).thenReturn(List.of(archive));
    var controller = new AdminDeletedUserController(users, archives, encryption, audit);
    var result = controller.list(null, 0, 20, null, null);
    assertEquals("a***@gmail.com", result.getItems().get(0).emailMasked());
    assertTrue(result.getItems().get(0).identityArchived());
    assertFalse(result.getItems().get(1).identityArchived());
    assertNull(result.getItems().get(1).emailMasked());
    assertFalse(result.toString().contains("ciphertext"));
    verifyNoInteractions(encryption);
  }

  @Test
  void revealDecryptsAndAuditsOnlyReasonAndTarget() {
    User deleted = User.builder().id(1L).status(UserStatus.DELETED).build();
    User admin = User.builder().id(3L).build();
    DeletedUserIdentityArchive archive = new DeletedUserIdentityArchive();
    archive.setUser(deleted);
    archive.setEmailEncrypted("encrypted-email");
    archive.setPhoneEncrypted("encrypted-phone");
    archive.setSlugAtDeletion("original-slug");
    when(archives.findByUser_Id(1L)).thenReturn(Optional.of(archive));
    when(encryption.decrypt("encrypted-email")).thenReturn("original@gmail.com");
    when(encryption.decrypt("encrypted-phone")).thenReturn("+77051234565");
    var controller = new AdminDeletedUserController(users, archives, encryption, audit);
    var result =
        controller.reveal(
            1L, new DeletedIdentifiersRevealRequest(true, "Fraud report #123"), admin, request);
    assertEquals("original@gmail.com", result.email());
    assertEquals("+77051234565", result.phone());
    assertEquals("original-slug", result.slug());
    ArgumentCaptor<AdminActionLog> log = ArgumentCaptor.forClass(AdminActionLog.class);
    verify(audit).save(log.capture());
    assertEquals(AdminActionType.USER_DELETED_IDENTIFIERS_REVEALED, log.getValue().getActionType());
    assertEquals("USER", log.getValue().getEntityType());
    assertEquals(1L, log.getValue().getEntityId());
    assertNull(log.getValue().getOldState());
    assertNull(log.getValue().getNewState());
  }

  @Test
  void revealRequestRequiresConfirmationAndReason() {
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      var validator = factory.getValidator();
      assertFalse(
          validator.validate(new DeletedIdentifiersRevealRequest(false, "Reason")).isEmpty());
      assertFalse(validator.validate(new DeletedIdentifiersRevealRequest(true, " ")).isEmpty());
      assertTrue(
          validator
              .validate(new DeletedIdentifiersRevealRequest(true, "Fraud report #123"))
              .isEmpty());
    }
  }
}
