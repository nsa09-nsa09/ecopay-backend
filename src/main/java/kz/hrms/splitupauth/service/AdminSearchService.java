package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.AdminSearchResultDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Global admin "spotlight" search across the three most-trafficked admin
 * sections (rooms, users, feedback). One round-trip, three small grouped
 * lists — used by the admin shell's top-bar quick-jump.
 *
 * <p>Each group is independently capped by {@code limit}; one underperforming
 * group never starves the others. All queries are ILIKE with a {@code %q%}
 * pattern so they pick up substrings anywhere in the field. Inputs are
 * validated and length-bounded so an empty / oversized query short-circuits
 * back to empty groups rather than scanning the world.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminSearchService {

    /** Caps the per-group result list — keeps the dropdown short. */
    public static final int DEFAULT_LIMIT = 5;
    public static final int MAX_LIMIT = 25;

    /** Anything longer is treated as garbage (typo, paste of a long string). */
    public static final int MAX_QUERY_LENGTH = 200;

    private final JdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public AdminSearchResultDto search(String q, Integer limit) {
        AdminSearchResultDto empty = AdminSearchResultDto.builder()
                .rooms(List.of()).users(List.of()).feedback(List.of()).build();

        if (q == null) return empty;
        String trimmed = q.trim();
        if (trimmed.isEmpty()) return empty;
        if (trimmed.length() > MAX_QUERY_LENGTH) {
            // Don't error out the admin shell — log it and return empty so the
            // FE renders "no results" rather than 500.
            log.warn("admin search query exceeds {} chars — returning empty groups", MAX_QUERY_LENGTH);
            return empty;
        }

        int cappedLimit = capLimit(limit);
        // ILIKE wants the wildcards literally — escape PG's own pattern metas
        // first so a user typing "100%" doesn't accidentally match everything.
        String pattern = "%" + escapeLikePattern(trimmed) + "%";

        return AdminSearchResultDto.builder()
                .rooms(searchRooms(pattern, cappedLimit))
                .users(searchUsers(pattern, cappedLimit))
                .feedback(searchFeedback(pattern, cappedLimit))
                .build();
    }

    private int capLimit(Integer requested) {
        if (requested == null || requested <= 0) return DEFAULT_LIMIT;
        return Math.min(requested, MAX_LIMIT);
    }

    /**
     * PG's LIKE treats {@code %} {@code _} {@code \\} as metacharacters. The
     * SQL below uses {@code ESCAPE '\'} so we can safely match literal
     * underscores in identifiers like "user_id_1".
     */
    private String escapeLikePattern(String input) {
        return input
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

    private List<AdminSearchResultDto.Item> searchRooms(String pattern, int limit) {
        String sql = "SELECT id, title FROM rooms "
                + "WHERE deleted_at IS NULL "
                + "  AND title ILIKE ? ESCAPE '\\' "
                + "ORDER BY created_at DESC LIMIT ?";
        try {
            return jdbc.query(sql, (rs, n) -> AdminSearchResultDto.Item.builder()
                    .id(rs.getLong("id"))
                    .type("room")
                    .label(rs.getString("title"))
                    .sublabel(null)
                    .build(), pattern, limit);
        } catch (RuntimeException ex) {
            log.warn("admin search 'rooms' failed: {}", ex.toString());
            return List.of();
        }
    }

    private List<AdminSearchResultDto.Item> searchUsers(String pattern, int limit) {
        // Match across email / displayName / publicId / phone in one go so the
        // operator doesn't have to pick a field. publicId is unique-short
        // (V17) so an exact paste of one will surface its owner directly.
        String sql = "SELECT id, email, display_name, public_id, phone, status FROM users "
                + "WHERE deleted_at IS NULL "
                + "  AND ( "
                + "        email         ILIKE ? ESCAPE '\\' "
                + "     OR display_name  ILIKE ? ESCAPE '\\' "
                + "     OR public_id     ILIKE ? ESCAPE '\\' "
                + "     OR COALESCE(phone, '') ILIKE ? ESCAPE '\\' "
                + "  ) "
                + "ORDER BY created_at DESC LIMIT ?";
        try {
            return jdbc.query(sql, (rs, n) -> {
                String email = rs.getString("email");
                String name = rs.getString("display_name");
                String phone = rs.getString("phone");
                String status = rs.getString("status");
                String label = (name != null && !name.isBlank())
                        ? name + " <" + email + ">"
                        : email;
                StringBuilder sub = new StringBuilder();
                if (phone != null && !phone.isBlank()) sub.append(phone);
                if (status != null) {
                    if (sub.length() > 0) sub.append(" · ");
                    sub.append(status);
                }
                return AdminSearchResultDto.Item.builder()
                        .id(rs.getLong("id"))
                        .type("user")
                        .label(label)
                        .sublabel(sub.length() == 0 ? null : sub.toString())
                        .build();
            }, pattern, pattern, pattern, pattern, limit);
        } catch (RuntimeException ex) {
            log.warn("admin search 'users' failed: {}", ex.toString());
            return List.of();
        }
    }

    private List<AdminSearchResultDto.Item> searchFeedback(String pattern, int limit) {
        String sql = "SELECT id, subject, message, status FROM feedback "
                + "WHERE subject ILIKE ? ESCAPE '\\' OR message ILIKE ? ESCAPE '\\' "
                + "ORDER BY created_at DESC LIMIT ?";
        try {
            return jdbc.query(sql, (rs, n) -> {
                String subject = rs.getString("subject");
                String message = rs.getString("message");
                String status = rs.getString("status");
                String label = (subject != null && !subject.isBlank())
                        ? subject
                        : truncate(message, 80);
                String sub = status;
                return AdminSearchResultDto.Item.builder()
                        .id(rs.getLong("id"))
                        .type("feedback")
                        .label(label)
                        .sublabel(sub)
                        .build();
            }, pattern, pattern, limit);
        } catch (RuntimeException ex) {
            log.warn("admin search 'feedback' failed: {}", ex.toString());
            return new ArrayList<>();
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
