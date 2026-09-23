package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;
import kz.hrms.splitupauth.dto.CreateUserReportRequest;
import kz.hrms.splitupauth.dto.UserReportStatusRequest;
import kz.hrms.splitupauth.entity.*;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceConflictException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.repository.AdminActionLogRepository;
import kz.hrms.splitupauth.repository.UserReportRepository;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

@ExtendWith(MockitoExtension.class)
class UserReportServiceTest {
  @Mock UserReportRepository reports;
  @Mock UserRepository users;
  @Mock AdminActionLogRepository audit;
  @Mock HttpServletRequest request;
  UserReportService service;
  User reporter;
  User target;

  @BeforeEach
  void setUp() {
    service = new UserReportService(reports, users, audit);
    reporter =
        User.builder()
            .id(1L)
            .publicId("reporter")
            .displayName("Reporter")
            .status(UserStatus.ACTIVE)
            .build();
    target =
        User.builder()
            .id(2L)
            .publicId("target")
            .slug("target-slug")
            .displayName("Target")
            .status(UserStatus.ACTIVE)
            .build();
  }

  @Test
  void createsReportFromHandleAndRejectsDuplicate() {
    when(users.findBySlug("target-slug")).thenReturn(Optional.of(target));
    when(users.findByIdForUpdate(2L)).thenReturn(Optional.of(target));
    when(reports.save(any()))
        .thenAnswer(
            inv -> {
              UserReport report = inv.getArgument(0);
              report.setId(5L);
              return report;
            });
    var body =
        new CreateUserReportRequest(
            UserReportCategory.FRAUD, "The account appears to be fraudulent");
    assertEquals(5L, service.create(reporter, "target-slug", body).id());
    ArgumentCaptor<UserReport> saved = ArgumentCaptor.forClass(UserReport.class);
    verify(reports).save(saved.capture());
    assertEquals(target, saved.getValue().getTargetUser());
    when(reports.existsByReporter_IdAndTargetUser_IdAndCategoryAndStatusIn(
            eq(1L), eq(2L), eq(UserReportCategory.FRAUD), any()))
        .thenReturn(true);
    ResourceConflictException error =
        assertThrows(
            ResourceConflictException.class, () -> service.create(reporter, "target-slug", body));
    assertEquals("USER_REPORT_ALREADY_OPEN", error.getCode());
  }

  @Test
  void rejectsSelfReportAndDeletedTarget() {
    when(users.findBySlug("target-slug")).thenReturn(Optional.of(target));
    when(users.findByIdForUpdate(2L)).thenReturn(Optional.of(target));
    var body =
        new CreateUserReportRequest(
            UserReportCategory.ABUSE, "A description with enough characters");
    target.setStatus(UserStatus.DELETED);
    assertThrows(
        ResourceNotFoundException.class, () -> service.create(reporter, "target-slug", body));
    target.setStatus(UserStatus.ACTIVE);
    target.setId(1L);
    when(users.findByIdForUpdate(1L)).thenReturn(Optional.of(target));
    assertThrows(
        InvalidRequestException.class, () -> service.create(reporter, "target-slug", body));
    verify(reports, never()).save(any());
  }

  @Test
  void assignmentAndResolutionAreAudited() {
    User admin = User.builder().id(3L).role(Role.ADMIN).status(UserStatus.ACTIVE).build();
    UserReport report = new UserReport();
    report.setId(9L);
    report.setReporter(reporter);
    report.setTargetUser(target);
    report.setStatus(UserReportStatus.OPEN);
    report.setCategory(UserReportCategory.FRAUD);
    when(reports.findById(9L)).thenReturn(Optional.of(report));
    when(reports.save(report)).thenReturn(report);
    service.assign(9L, null, admin, request);
    assertEquals(3L, report.getAssignedAdmin().getId());
    service.changeStatus(
        9L,
        new UserReportStatusRequest(UserReportStatus.RESOLVED, "Investigation complete"),
        admin,
        request);
    assertNotNull(report.getResolvedAt());
    ArgumentCaptor<AdminActionLog> logs = ArgumentCaptor.forClass(AdminActionLog.class);
    verify(audit, times(2)).save(logs.capture());
    assertEquals(AdminActionType.USER_REPORT_ASSIGNED, logs.getAllValues().get(0).getActionType());
    assertEquals(
        AdminActionType.USER_REPORT_STATUS_CHANGED, logs.getAllValues().get(1).getActionType());
  }

  @Test
  void adminListAndDetailExposeSafeIdentityFields() {
    reporter.setEmail("reporter@private.kz");
    target.setPhone("+77051234565");
    UserReport report = new UserReport();
    report.setId(10L);
    report.setReporter(reporter);
    report.setTargetUser(target);
    report.setCategory(UserReportCategory.SPAM);
    report.setStatus(UserReportStatus.OPEN);
    report.setDescription("Repeated unsolicited messages");
    when(reports.findAll(any(Specification.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of(report)));
    when(reports.findById(10L)).thenReturn(Optional.of(report));
    var page = service.list(UserReportStatus.OPEN, UserReportCategory.SPAM, 2L, 0, 20);
    assertEquals(10L, page.getItems().get(0).id());
    assertNull(page.getItems().get(0).description());
    assertFalse(page.toString().contains("reporter@private.kz"));
    assertFalse(page.toString().contains("+77051234565"));
    assertEquals("Repeated unsolicited messages", service.get(10L).description());
  }
}
