package kz.hrms.splitupauth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records site visits with per-day deduplication keyed by an HttpOnly "vid" cookie. The first POST
 * per (visitor, calendar day in Almaty) inserts a row; subsequent hits the same day bump page_count
 * without creating duplicates.
 *
 * <p>The cookie is the source of truth for uniqueness, NOT IP — IPs are shared by NAT/VPNs and
 * would dramatically over-count families behind one router. Rate limiting (per visitor + per IP) is
 * layered on to slow down trivial cookie-clearing inflation attempts.
 */
@Service
@RequiredArgsConstructor
public class SiteVisitService {

  public static final String COOKIE_NAME = "vid";

  /** ~1 year, matches the typical browser cap on persistent cookies. */
  public static final int COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

  private static final int MAX_PATH_LENGTH = 255;

  /**
   * Single atomic UPSERT replacing the old find → mutate → save (which issued 2–3 statements and
   * raced on the first hit of the day). {@code RETURNING (xmax = 0)} is Postgres's insert-vs-update
   * discriminator: true when the row was freshly inserted (a new unique visitor for the day). The
   * update branch mirrors the previous mutation exactly: bump page_count, refresh last_seen, keep a
   * non-null path, and only ever upgrade is_authenticated / attach the user (never clear them).
   */
  private static final String UPSERT_SQL =
      "INSERT INTO site_visit (visitor_id, visit_date, first_seen_at, last_seen_at, page_count, "
          + "is_authenticated, user_id, last_path) "
          + "VALUES (?, ?, ?, ?, 1, ?, CAST(? AS bigint), CAST(? AS varchar)) "
          + "ON CONFLICT (visitor_id, visit_date) DO UPDATE SET "
          + "last_seen_at = EXCLUDED.last_seen_at, "
          + "page_count = site_visit.page_count + 1, "
          + "last_path = COALESCE(EXCLUDED.last_path, site_visit.last_path), "
          + "is_authenticated = (site_visit.is_authenticated OR EXCLUDED.is_authenticated), "
          + "user_id = COALESCE(site_visit.user_id, EXCLUDED.user_id) "
          + "RETURNING (xmax = 0)";

  private final InMemoryRateLimiter rateLimiter;
  private final JdbcTemplate jdbcTemplate;

  public record VisitResult(UUID visitorId, boolean newVisitorToday) {}

  /**
   * Records a visit for the cookie-borne visitor id (issuing a new cookie if absent). Returns the
   * visitor id so the controller can write the cookie.
   */
  @Transactional
  public VisitResult recordVisit(
      HttpServletRequest request,
      HttpServletResponse response,
      String path,
      User authenticatedUser) {
    UUID visitorId = readOrIssueVisitorId(request, response);

    String ip = clientIp(request);
    // Rate limit by IP and visitor so a script can't inflate counters from one box.
    rateLimiter.check("visit:ip:" + ip, 30, 60, "Too many visit pings from this address");
    rateLimiter.check("visit:vid:" + visitorId, 60, 60, "Too many visit pings");

    LocalDate today = LocalDate.now();
    LocalDateTime now = LocalDateTime.now();
    String truncatedPath =
        path != null && path.length() > MAX_PATH_LENGTH ? path.substring(0, MAX_PATH_LENGTH) : path;

    Long userId = authenticatedUser == null ? null : authenticatedUser.getId();
    Boolean inserted =
        jdbcTemplate.queryForObject(
            UPSERT_SQL,
            Boolean.class,
            visitorId,
            today,
            now,
            now,
            authenticatedUser != null,
            userId,
            truncatedPath);
    return new VisitResult(visitorId, Boolean.TRUE.equals(inserted));
  }

  private UUID readOrIssueVisitorId(HttpServletRequest request, HttpServletResponse response) {
    if (request.getCookies() != null) {
      for (Cookie cookie : request.getCookies()) {
        if (COOKIE_NAME.equals(cookie.getName())) {
          try {
            return UUID.fromString(cookie.getValue());
          } catch (IllegalArgumentException ignored) {
            // fall through and re-issue
          }
        }
      }
    }
    UUID fresh = UUID.randomUUID();
    Cookie cookie = new Cookie(COOKIE_NAME, fresh.toString());
    cookie.setHttpOnly(true);
    cookie.setPath("/");
    cookie.setMaxAge(COOKIE_MAX_AGE_SECONDS);
    // Lax — analytics never needs cross-site cookies and Strict would drop
    // the cookie on the initial inbound navigation.
    cookie.setAttribute("SameSite", "Lax");
    cookie.setSecure(request.isSecure());
    response.addCookie(cookie);
    return fresh;
  }

  private String clientIp(HttpServletRequest request) {
    String forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      int comma = forwarded.indexOf(',');
      return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
    }
    return request.getRemoteAddr();
  }
}
