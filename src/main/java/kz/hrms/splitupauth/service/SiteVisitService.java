package kz.hrms.splitupauth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.util.ClientIp;
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

  private static final String UPSERT_VISIT =
      "INSERT INTO site_visit (visitor_id, visit_date, first_seen_at, last_seen_at, page_count,"
          + " is_authenticated, user_id, last_path) VALUES (?, ?, ?, ?, 1, ?, ?, ?)"
          + " ON CONFLICT (visitor_id, visit_date) DO UPDATE SET"
          + " last_seen_at = EXCLUDED.last_seen_at,"
          + " page_count = site_visit.page_count + 1,"
          + " is_authenticated = site_visit.is_authenticated OR EXCLUDED.is_authenticated,"
          + " user_id = COALESCE(site_visit.user_id, EXCLUDED.user_id),"
          + " last_path = COALESCE(EXCLUDED.last_path, site_visit.last_path)"
          + " RETURNING page_count";

  private final JdbcTemplate jdbcTemplate;
  private final RateLimiter rateLimiter;

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
    String sanitizedPath = sanitizePath(path);
    Long userId = authenticatedUser == null ? null : authenticatedUser.getId();

    // One atomic statement per ping: no read-modify-write round trip, no lost increments under
    // concurrent pings, and no unique-violation retry path.
    Integer pageCount =
        jdbcTemplate.queryForObject(
            UPSERT_VISIT,
            Integer.class,
            visitorId,
            today,
            now,
            now,
            userId != null,
            userId,
            sanitizedPath);
    return new VisitResult(visitorId, pageCount != null && pageCount == 1);
  }

  /**
   * Keeps only the route path: query strings and fragments can carry password-reset tokens, payment
   * ids or other identifiers that analytics has no use for.
   */
  static String sanitizePath(String path) {
    if (path == null || path.isBlank()) {
      return null;
    }
    String trimmed = path.trim();
    int cut = trimmed.length();
    int q = trimmed.indexOf('?');
    if (q >= 0) cut = Math.min(cut, q);
    int h = trimmed.indexOf('#');
    if (h >= 0) cut = Math.min(cut, h);
    trimmed = trimmed.substring(0, cut);
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > MAX_PATH_LENGTH ? trimmed.substring(0, MAX_PATH_LENGTH) : trimmed;
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
    return ClientIp.of(request);
  }
}
