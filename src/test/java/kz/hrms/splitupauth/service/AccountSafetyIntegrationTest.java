package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.controller.AdminDeletedUserController;
import kz.hrms.splitupauth.controller.AdminUserInvestigationController;
import kz.hrms.splitupauth.dto.CreateUserReportRequest;
import kz.hrms.splitupauth.dto.DeletedIdentifiersRevealRequest;
import kz.hrms.splitupauth.dto.RegisterRequest;
import kz.hrms.splitupauth.dto.UserReportStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.UserAlreadyExistsException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.DeletedUserIdentityArchiveRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import kz.hrms.splitupauth.security.FieldEncryptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

class AccountSafetyIntegrationTest extends AbstractIntegrationTest {
  private static final AtomicInteger IDS = new AtomicInteger();

  @Autowired AuthService authService;
  @Autowired UserService userService;
  @Autowired UserReportService reportService;
  @Autowired UserRepository users;
  @Autowired DeletedUserIdentityArchiveRepository archives;
  @Autowired FieldEncryptionService encryption;
  @Autowired AdminDeletedUserController deletedController;
  @Autowired AdminUserInvestigationController investigationController;
  @Autowired AdminActionLogRepository actions;
  @Autowired SlugService slugService;

  private User register() {
    RegisterRequest request = new RegisterRequest();
    request.setEmail(
        "account_safety_" + IDS.incrementAndGet() + "_" + System.nanoTime() + "@test.kz");
    request.setPassword("Test1234");
    request.setDisplayName("Account safety test");
    authService.register(request, MailLocale.EN, null);
    return users.findByEmail(request.getEmail()).orElseThrow();
  }

  private String uniquePhone() {
    return String.format("+7705%07d", Math.floorMod(System.nanoTime(), 10_000_000));
  }

  @Test
  void deletionArchivesEncryptedIdentityAndRevealAuditsWithoutPii() {
    User user = register();
    String email = user.getEmail();
    String phone = uniquePhone();
    user.setPhone(phone);
    user = users.save(user);
    String slug = user.getSlug();
    userService.deleteAccount(user);

    User stored = users.findById(user.getId()).orElseThrow();
    assertEquals(UserStatus.DELETED, stored.getStatus());
    assertTrue(stored.getEmail().startsWith("deleted-"));
    assertNull(stored.getPhone());
    var archive = archives.findByUser_Id(user.getId()).orElseThrow();
    assertNotEquals(email, archive.getEmailEncrypted());
    assertNotEquals(phone, archive.getPhoneEncrypted());
    assertEquals(email, encryption.decrypt(archive.getEmailEncrypted()));
    assertEquals(phone, encryption.decrypt(archive.getPhoneEncrypted()));
    assertEquals("+7705*****" + phone.substring(phone.length() - 2), archive.getPhoneMasked());
    assertEquals(slug, archive.getSlugAtDeletion());

    var list = deletedController.list(slug, 0, 20, null, null);
    Long deletedUserId = user.getId();
    assertTrue(
        list.getItems().stream()
            .anyMatch(
                i ->
                    i.userId().equals(deletedUserId)
                        && i.identityArchived()
                        && i.emailMasked() != null));
    assertFalse(list.toString().contains(email));
    User admin = register();
    admin.setRole(Role.ADMIN);
    admin = users.save(admin);
    var reveal =
        deletedController.reveal(
            user.getId(),
            new DeletedIdentifiersRevealRequest(true, "Fraud report #123"),
            admin,
            new MockHttpServletRequest());
    assertEquals(email, reveal.email());
    assertEquals(phone, reveal.phone());
    var audit =
        actions
            .findFirstByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtDesc(
                "USER", user.getId(), AdminActionType.USER_DELETED_IDENTIFIERS_REVEALED)
            .orElseThrow();
    assertNull(audit.getOldState());
    assertNull(audit.getNewState());
  }

  @Test
  void reportsAreScopedAndBannedIdentifiersRemainReserved() {
    User reporter = register();
    User target = register();
    User other = register();
    var body =
        new CreateUserReportRequest(
            UserReportCategory.FRAUD, "The profile appears to be fraudulent");
    var report = reportService.create(reporter, target.getSlug(), body);
    String targetHandle = target.getSlug();
    assertEquals(UserReportStatus.OPEN, report.status());
    assertThrows(
        ResourceConflictException.class, () -> reportService.create(reporter, targetHandle, body));
    var context = investigationController.get(target.getId());
    assertTrue(context.reportsAgainst().stream().anyMatch(r -> r.id().equals(report.id())));
    assertTrue(context.reportsBy().isEmpty());
    assertTrue(investigationController.get(other.getId()).reportsAgainst().isEmpty());

    User admin = register();
    admin.setRole(Role.ADMIN);
    admin = users.save(admin);
    reportService.assign(report.id(), null, admin, new MockHttpServletRequest());
    reportService.changeStatus(
        report.id(),
        new UserReportStatusRequest(UserReportStatus.RESOLVED, "Reviewed and resolved"),
        admin,
        new MockHttpServletRequest());
    assertTrue(
        actions
            .findFirstByEntityTypeAndEntityIdAndActionTypeOrderByCreatedAtDesc(
                "USER_REPORT", report.id(), AdminActionType.USER_REPORT_STATUS_CHANGED)
            .isPresent());

    target.setStatus(UserStatus.BANNED);
    target.setPhone(uniquePhone());
    target = users.save(target);
    assertTrue(users.existsByEmail(target.getEmail()));
    assertTrue(users.existsByPhone(target.getPhone()));
    assertTrue(slugService.isTaken(target.getSlug(), reporter.getId()));
    RegisterRequest duplicate = new RegisterRequest();
    duplicate.setEmail(target.getEmail());
    duplicate.setPassword("Test1234");
    duplicate.setDisplayName("Duplicate");
    assertThrows(
        UserAlreadyExistsException.class,
        () -> authService.register(duplicate, MailLocale.EN, null));
  }
}
