package kz.hrms.splitupauth.service;

import jakarta.servlet.http.HttpServletRequest;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.AdminFeedbackDto;
import kz.hrms.splitupauth.dto.CreateFeedbackRequest;
import kz.hrms.splitupauth.dto.FeedbackDto;
import kz.hrms.splitupauth.dto.PagedResponse;
import kz.hrms.splitupauth.dto.UpdateFeedbackRequest;
import kz.hrms.splitupauth.entity.FeedbackStatus;
import kz.hrms.splitupauth.entity.FeedbackType;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.TooManyLoginAttemptsException;
import kz.hrms.splitupauth.exception.TooManyRequestsException;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the feedback inbox: user submit + listMine,
 * per-user/per-IP rate-limit triggers a 429, admin filter/get/update, and the
 * AdminActionLog audit row gets written.
 */
class FeedbackServiceTest extends AbstractIntegrationTest {

    @Autowired FeedbackService feedbackService;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbc;

    private static final AtomicInteger SEQ = new AtomicInteger();

    private User user(String prefix) {
        int n = SEQ.incrementAndGet();
        return userRepository.save(User.builder()
                .email("fb_" + prefix + "_" + n + "_" + System.nanoTime() + "@t.kz")
                .password("x")
                .displayName(prefix + " " + n)
                .role(Role.USER)
                .status(UserStatus.ACTIVE)
                .build());
    }

    private User admin() {
        User u = user("adminFb");
        u.setRole(Role.ADMIN);
        return userRepository.save(u);
    }

    private CreateFeedbackRequest request(FeedbackType type, String message) {
        CreateFeedbackRequest req = new CreateFeedbackRequest();
        req.setType(type);
        req.setSubject("Subj " + SEQ.incrementAndGet());
        req.setMessage(message);
        return req;
    }

    // ===================== user surface =====================

    @Test
    void submit_storesRowAndReturnsDto() {
        User u = user("submit");
        FeedbackDto created = feedbackService.submit(
                u, request(FeedbackType.IDEA, "Please add dark theme"));

        assertNotNull(created.getId());
        assertEquals(FeedbackType.IDEA, created.getType());
        assertEquals(FeedbackStatus.NEW, created.getStatus());
        assertEquals("Please add dark theme", created.getMessage());
    }

    @Test
    void listMine_returnsOnlyOwnSubmissions() {
        User a = user("listA");
        User b = user("listB");
        feedbackService.submit(a, request(FeedbackType.COMPLAINT, "Outage at 3am"));
        feedbackService.submit(b, request(FeedbackType.REQUEST, "Different user — must not appear"));

        PagedResponse<FeedbackDto> mine = feedbackService.listMine(a, 0, 20);
        assertTrue(mine.getItems().stream().allMatch(f ->
                "Outage at 3am".equals(f.getMessage())),
                "list-mine must scope to the calling user");
    }

    // ===================== rate-limit (per-user) =====================

    @Test
    void perUserRateLimit_429AfterCapHit() {
        User u = user("rate");
        // Dial the per-user limit down to 2 so we don't have to write 5 rows.
        ReflectionTestUtils.setField(feedbackService, "maxPerHour", 2);
        // Disable the IP limit for this test — we're isolating the user limit.
        ReflectionTestUtils.setField(feedbackService, "ipMaxPerHour", 0);

        feedbackService.submit(u, request(FeedbackType.IDEA, "msg 1"));
        feedbackService.submit(u, request(FeedbackType.IDEA, "msg 2"));

        assertThrows(TooManyLoginAttemptsException.class,
                () -> feedbackService.submit(u, request(FeedbackType.IDEA, "msg 3")),
                "3rd submission within the hour must fail the per-user limit");

        // Reset for other tests in the same context.
        ReflectionTestUtils.setField(feedbackService, "maxPerHour", 5);
        ReflectionTestUtils.setField(feedbackService, "ipMaxPerHour", 20);
    }

    // ===================== rate-limit (per-IP) =====================

    @Test
    void perIpRateLimit_429AfterCapHit() {
        // Two users from the same IP — the per-IP limiter should still cut
        // them off so one actor can't cycle accounts.
        ReflectionTestUtils.setField(feedbackService, "ipMaxPerHour", 2);
        ReflectionTestUtils.setField(feedbackService, "maxPerHour", 100);

        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setRemoteAddr("203.0.113.55");

        feedbackService.submit(user("ip1"), request(FeedbackType.IDEA, "msg 1"), http);
        feedbackService.submit(user("ip2"), request(FeedbackType.IDEA, "msg 2"), http);

        assertThrows(TooManyRequestsException.class,
                () -> feedbackService.submit(user("ip3"),
                        request(FeedbackType.IDEA, "msg 3"), http),
                "3rd submission from the same IP within the hour must be blocked");

        ReflectionTestUtils.setField(feedbackService, "ipMaxPerHour", 20);
        ReflectionTestUtils.setField(feedbackService, "maxPerHour", 5);
    }

    // ===================== admin surface + audit log =====================

    @Test
    void adminPatch_changesStatusAndWritesAuditRow() {
        User u = user("adminFlow");
        FeedbackDto created = feedbackService.submit(u, request(FeedbackType.COMPLAINT, "Ticket-like"));

        User adminUser = admin();
        long auditBefore = jdbc.queryForObject(
                "select count(*) from admin_action_log where action_type = 'FEEDBACK_STATUS_CHANGED'",
                Long.class);

        UpdateFeedbackRequest patch = new UpdateFeedbackRequest();
        patch.setStatus(FeedbackStatus.IN_REVIEW);
        AdminFeedbackDto updated = feedbackService.adminUpdate(
                adminUser, created.getId(), patch, new MockHttpServletRequest());

        assertEquals(FeedbackStatus.IN_REVIEW, updated.getStatus());
        assertEquals(adminUser.getId(), updated.getHandledByUserId());

        long auditAfter = jdbc.queryForObject(
                "select count(*) from admin_action_log where action_type = 'FEEDBACK_STATUS_CHANGED'",
                Long.class);
        assertEquals(auditBefore + 1, auditAfter,
                "status change must append a FEEDBACK_STATUS_CHANGED row");
    }

    @Test
    void adminPatch_emptyBody_rejected() {
        User u = user("emptyPatch");
        FeedbackDto created = feedbackService.submit(u, request(FeedbackType.REQUEST, "Empty patch test"));

        UpdateFeedbackRequest empty = new UpdateFeedbackRequest();
        assertThrows(InvalidRequestException.class,
                () -> feedbackService.adminUpdate(admin(), created.getId(), empty,
                        new MockHttpServletRequest()));
    }

    @Test
    void adminList_filtersByTypeAndStatus() {
        User u = user("filt");
        feedbackService.submit(u, request(FeedbackType.IDEA, "idea 1"));
        feedbackService.submit(u, request(FeedbackType.COMPLAINT, "complaint 1"));

        PagedResponse<AdminFeedbackDto> ideas = feedbackService.adminList(
                FeedbackType.IDEA, null, null, 0, 20);
        assertTrue(ideas.getItems().stream().allMatch(f -> f.getType() == FeedbackType.IDEA));

        PagedResponse<AdminFeedbackDto> newOnes = feedbackService.adminList(
                null, FeedbackStatus.NEW, null, 0, 20);
        assertTrue(newOnes.getItems().stream().allMatch(f -> f.getStatus() == FeedbackStatus.NEW));
    }
}
