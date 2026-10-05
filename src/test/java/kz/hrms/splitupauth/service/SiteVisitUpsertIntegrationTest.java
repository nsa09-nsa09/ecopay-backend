package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.http.Cookie;
import java.time.LocalDate;
import java.util.UUID;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Block 7: {@code recordVisit} is now a single atomic UPSERT. First hit of the day for a visitor
 * inserts (newVisitorToday=true); subsequent hits increment page_count without duplicating the row
 * or racing; a null path never clears a previously-stored path.
 */
class SiteVisitUpsertIntegrationTest extends AbstractIntegrationTest {

  @Autowired SiteVisitService siteVisitService;
  @Autowired JdbcTemplate jdbcTemplate;

  private MockHttpServletRequest requestFor(UUID visitorId) {
    MockHttpServletRequest req = new MockHttpServletRequest();
    req.setCookies(new Cookie(SiteVisitService.COOKIE_NAME, visitorId.toString()));
    req.setRemoteAddr("198.51.100.77");
    return req;
  }

  @Test
  void firstHitInserts_subsequentHitsIncrement_withoutDuplicateRows() {
    UUID visitor = UUID.randomUUID();

    var first =
        siteVisitService.recordVisit(
            requestFor(visitor), new MockHttpServletResponse(), "/home", null);
    assertTrue(first.newVisitorToday(), "first hit of the day must count as a new unique visitor");

    var second =
        siteVisitService.recordVisit(
            requestFor(visitor), new MockHttpServletResponse(), "/rooms", null);
    assertFalse(second.newVisitorToday(), "same visitor same day is not a new unique visitor");

    // A third hit with a null path must not wipe the stored path.
    siteVisitService.recordVisit(requestFor(visitor), new MockHttpServletResponse(), null, null);

    Integer rows =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM site_visit WHERE visitor_id = ? AND visit_date = ?",
            Integer.class,
            visitor,
            LocalDate.now());
    assertEquals(1, rows, "exactly one row per (visitor, day) — no duplicates");

    Integer pageCount =
        jdbcTemplate.queryForObject(
            "SELECT page_count FROM site_visit WHERE visitor_id = ? AND visit_date = ?",
            Integer.class,
            visitor,
            LocalDate.now());
    assertEquals(3, pageCount, "page_count must atomically increment per hit");

    String lastPath =
        jdbcTemplate.queryForObject(
            "SELECT last_path FROM site_visit WHERE visitor_id = ? AND visit_date = ?",
            String.class,
            visitor,
            LocalDate.now());
    assertEquals("/rooms", lastPath, "a null-path hit must not clear the previous path");
  }
}
