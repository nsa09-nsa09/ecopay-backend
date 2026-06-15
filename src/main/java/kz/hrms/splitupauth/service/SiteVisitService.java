package kz.hrms.splitupauth.service;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import kz.hrms.splitupauth.entity.SiteVisit;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.repository.SiteVisitRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Records site visits with per-day deduplication keyed by an HttpOnly "vid"
 * cookie. The first POST per (visitor, calendar day in Almaty) inserts a row;
 * subsequent hits the same day bump page_count without creating duplicates.
 *
 * <p>The cookie is the source of truth for uniqueness, NOT IP — IPs are shared
 * by NAT/VPNs and would dramatically over-count families behind one router.
 * Rate limiting (per visitor + per IP) is layered on to slow down trivial
 * cookie-clearing inflation attempts.
 */
@Service
@RequiredArgsConstructor
public class SiteVisitService {

    public static final String COOKIE_NAME = "vid";
    /** ~1 year, matches the typical browser cap on persistent cookies. */
    public static final int COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 365;

    private static final int MAX_PATH_LENGTH = 255;

    private final SiteVisitRepository repository;
    private final InMemoryRateLimiter rateLimiter;

    public record VisitResult(UUID visitorId, boolean newVisitorToday) {}

    /**
     * Records a visit for the cookie-borne visitor id (issuing a new cookie if
     * absent). Returns the visitor id so the controller can write the cookie.
     */
    @Transactional
    public VisitResult recordVisit(HttpServletRequest request,
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
        String truncatedPath = path != null && path.length() > MAX_PATH_LENGTH
                ? path.substring(0, MAX_PATH_LENGTH)
                : path;

        Optional<SiteVisit> existing = repository.findByVisitorIdAndVisitDate(visitorId, today);
        if (existing.isPresent()) {
            SiteVisit v = existing.get();
            v.setLastSeenAt(now);
            v.setPageCount(v.getPageCount() == null ? 1 : v.getPageCount() + 1);
            if (truncatedPath != null) {
                v.setLastPath(truncatedPath);
            }
            if (authenticatedUser != null) {
                v.setIsAuthenticated(true);
                if (v.getUser() == null) {
                    v.setUser(authenticatedUser);
                }
            }
            repository.save(v);
            return new VisitResult(visitorId, false);
        }

        SiteVisit fresh = SiteVisit.builder()
                .visitorId(visitorId)
                .visitDate(today)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .pageCount(1)
                .isAuthenticated(authenticatedUser != null)
                .user(authenticatedUser)
                .lastPath(truncatedPath)
                .build();
        try {
            repository.save(fresh);
            return new VisitResult(visitorId, true);
        } catch (DataIntegrityViolationException race) {
            // Concurrent first-of-day insert from another request — fall back to update.
            SiteVisit other = repository.findByVisitorIdAndVisitDate(visitorId, today)
                    .orElseThrow(() -> race);
            other.setLastSeenAt(now);
            other.setPageCount(other.getPageCount() == null ? 1 : other.getPageCount() + 1);
            if (truncatedPath != null) {
                other.setLastPath(truncatedPath);
            }
            if (authenticatedUser != null) {
                other.setIsAuthenticated(true);
                if (other.getUser() == null) {
                    other.setUser(authenticatedUser);
                }
            }
            repository.save(other);
            return new VisitResult(visitorId, false);
        }
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
