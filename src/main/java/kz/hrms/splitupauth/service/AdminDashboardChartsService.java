package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.DashboardLabelValueDto;
import kz.hrms.splitupauth.dto.OperatorDistributionDto;
import kz.hrms.splitupauth.dto.PopularServiceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Read-only aggregates that power the admin dashboard's distribution charts
 * (popular services / operators / currencies / categories / room statuses).
 *
 * <p>Each chart is exposed as its own endpoint and each runs its own SQL — so
 * one failing query (e.g. an upstream schema drift) cannot black out the whole
 * dashboard. The service wraps every query in {@link #safeChart(String, Supplier)}
 * which logs the exception and returns an empty list to the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardChartsService {

    /**
     * KZ mobile DEF-codes → carrier. Source: official MNO numbering plan. The
     * map is intentionally small and editable — adding a new code is a one-line
     * change here, no migration needed.
     */
    private static final Map<String, String> OPERATOR_BY_DEF_CODE = buildOperatorMap();

    private static final String OTHER_OPERATOR_CODE = "OTHER";
    private static final String OTHER_OPERATOR_NAME = "Другое";
    private static final String UNCATEGORISED_LABEL = "Без категории";

    private static final int DEFAULT_POPULAR_LIMIT = 10;
    private static final int MAX_POPULAR_LIMIT = 100;

    private final JdbcTemplate jdbc;

    // ===================== popular services =====================

    @Transactional(readOnly = true)
    public List<PopularServiceDto> popularServices(Integer limit) {
        int capped = clampLimit(limit);

        // Join active members per room ahead of grouping by service so the
        // rank reflects "people actually paying", not just "rooms posted".
        String sql =
                "SELECT s.id AS service_id, s.name AS service_name, "
                        + "       COUNT(DISTINCT r.id) AS rooms_count, "
                        + "       COALESCE(SUM(am.active_members), 0) AS active_members "
                        + "FROM services s "
                        + "JOIN rooms r ON r.service_id = s.id AND r.deleted_at IS NULL "
                        + "LEFT JOIN ( "
                        + "  SELECT room_id, COUNT(*) AS active_members "
                        + "  FROM room_members "
                        + "  WHERE deleted_at IS NULL AND status = 'ACTIVE' "
                        + "  GROUP BY room_id "
                        + ") am ON am.room_id = r.id "
                        + "GROUP BY s.id, s.name "
                        + "ORDER BY rooms_count DESC, active_members DESC, s.name ASC "
                        + "LIMIT ?";

        return safeChart("popular-services", () ->
                jdbc.query(sql, (rs, rowNum) -> PopularServiceDto.builder()
                        .serviceId(rs.getLong("service_id"))
                        .serviceName(rs.getString("service_name"))
                        .roomsCount(rs.getLong("rooms_count"))
                        .activeMembersCount(rs.getLong("active_members"))
                        .build(), capped));
    }

    private int clampLimit(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_POPULAR_LIMIT;
        }
        return Math.min(requested, MAX_POPULAR_LIMIT);
    }

    // ===================== operator distribution =====================

    @Transactional(readOnly = true)
    public List<OperatorDistributionDto> operatorDistribution() {
        // Normalise the phone to digits in SQL, then look at the 3 digits
        // immediately after the leading "7" country code. Foreign numbers /
        // missing phones land in the OTHER bucket.
        //
        // The OTHER literal is inlined (rather than a bind parameter) because
        // Postgres can't infer the type of an unparameterised "?" branch in a
        // CASE / GROUP BY and rejects the query with a BadSqlGrammarException.
        String sql =
                "SELECT def_code, COUNT(*) AS cnt FROM ( "
                        + "  SELECT CASE "
                        + "    WHEN u.phone IS NULL OR u.phone = '' THEN 'OTHER' "
                        + "    WHEN LENGTH(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g')) <> 11 THEN 'OTHER' "
                        + "    WHEN SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 1 FOR 1) <> '7' THEN 'OTHER' "
                        + "    ELSE SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 2 FOR 3) "
                        + "  END AS def_code "
                        + "  FROM users u "
                        + "  WHERE u.deleted_at IS NULL "
                        + ") buckets "
                        + "GROUP BY def_code "
                        + "ORDER BY cnt DESC";

        return safeChart("operator-distribution", () -> {
            Map<String, Long> raw = new LinkedHashMap<>();
            // Explicit RowCallbackHandler — without it the lambda is ambiguous
            // with ResultSetExtractor on Spring 7's overloaded JdbcTemplate.
            org.springframework.jdbc.core.RowCallbackHandler handler =
                    rs -> raw.put(rs.getString("def_code"), rs.getLong("cnt"));
            jdbc.query(sql, handler);

            // Re-bucket DEF codes we don't recognize into OTHER so the FE
            // doesn't have to render dozens of one-off codes that don't map.
            long otherCount = raw.getOrDefault(OTHER_OPERATOR_CODE, 0L);
            List<OperatorDistributionDto> result = new ArrayList<>();
            for (Map.Entry<String, Long> e : raw.entrySet()) {
                String code = e.getKey();
                if (OTHER_OPERATOR_CODE.equals(code)) continue;
                String name = OPERATOR_BY_DEF_CODE.get(code);
                if (name == null) {
                    otherCount += e.getValue();
                    continue;
                }
                result.add(OperatorDistributionDto.builder()
                        .code(code)
                        .operatorName(name)
                        .count(e.getValue())
                        .build());
            }
            if (otherCount > 0) {
                result.add(OperatorDistributionDto.builder()
                        .code(OTHER_OPERATOR_CODE)
                        .operatorName(OTHER_OPERATOR_NAME)
                        .count(otherCount)
                        .build());
            }
            result.sort((a, b) -> Long.compare(b.getCount(), a.getCount()));
            return result;
        });
    }

    // ===================== currency distribution =====================

    @Transactional(readOnly = true)
    public List<DashboardLabelValueDto> currencyDistribution() {
        String sql = "SELECT COALESCE(currency, 'KZT') AS code, COUNT(*) AS cnt "
                + "FROM rooms WHERE deleted_at IS NULL AND status = 'ACTIVE' "
                + "GROUP BY COALESCE(currency, 'KZT') "
                + "ORDER BY cnt DESC, code ASC";
        return safeChart("currency-distribution", () ->
                jdbc.query(sql, (rs, n) -> DashboardLabelValueDto.builder()
                        .label(rs.getString("code"))
                        .value(rs.getLong("cnt"))
                        .build()));
    }

    // ===================== category distribution =====================

    @Transactional(readOnly = true)
    public List<DashboardLabelValueDto> categoryDistribution() {
        // Group on the raw column (NULLs collapse into one group naturally) so
        // we don't need a bind parameter inside GROUP BY — Postgres rejects
        // those with "could not determine data type of parameter". The
        // user-visible label is substituted in the SELECT only.
        String sql = "SELECT COALESCE(c.name, '" + escapeLiteral(UNCATEGORISED_LABEL) + "') AS label, COUNT(*) AS cnt "
                + "FROM rooms r LEFT JOIN categories c ON c.id = r.category_id "
                + "WHERE r.deleted_at IS NULL "
                + "GROUP BY c.name "
                + "ORDER BY cnt DESC, label ASC";
        return safeChart("category-distribution", () ->
                jdbc.query(sql, (rs, n) -> DashboardLabelValueDto.builder()
                        .label(rs.getString("label"))
                        .value(rs.getLong("cnt"))
                        .build()));
    }

    // ===================== room status distribution =====================

    @Transactional(readOnly = true)
    public List<DashboardLabelValueDto> roomStatusDistribution() {
        String sql = "SELECT status AS label, COUNT(*) AS cnt "
                + "FROM rooms WHERE deleted_at IS NULL "
                + "GROUP BY status "
                + "ORDER BY cnt DESC, status ASC";
        return safeChart("room-status-distribution", () ->
                jdbc.query(sql, (rs, n) -> DashboardLabelValueDto.builder()
                        .label(rs.getString("label"))
                        .value(rs.getLong("cnt"))
                        .build()));
    }

    // ===================== helpers =====================

    /**
     * Isolate one chart from another. A schema drift in (say) the operator
     * SQL must not blank out the popular-services chart that the FE renders
     * in the same row. The caller still sees a 200 with an empty list; the
     * cause is in the server log so it can be diagnosed without paging the
     * admin user.
     */
    private <T> List<T> safeChart(String chartName, Supplier<List<T>> query) {
        try {
            List<T> rows = query.get();
            return rows != null ? rows : Collections.emptyList();
        } catch (RuntimeException ex) {
            log.warn("dashboard chart '{}' failed: {}", chartName, ex.toString());
            return Collections.emptyList();
        }
    }

    /** Escape single quotes for a SQL string literal. UNCATEGORISED_LABEL has none, but guard anyway. */
    private String escapeLiteral(String value) {
        return value.replace("'", "''");
    }

    private static Map<String, String> buildOperatorMap() {
        Map<String, String> m = new LinkedHashMap<>();
        // Kcell-Activ (Kcell JSC, includes Activ).
        m.put("700", "Kcell-Activ");
        m.put("701", "Kcell-Activ");
        m.put("702", "Kcell-Activ");
        m.put("775", "Kcell-Activ");
        m.put("778", "Kcell-Activ");
        // Beeline KZ.
        m.put("705", "Beeline");
        m.put("771", "Beeline");
        m.put("776", "Beeline");
        m.put("777", "Beeline");
        // Tele2-Altel (Mobile Telecom-Service brands).
        m.put("707", "Tele2-Altel");
        m.put("708", "Tele2-Altel");
        m.put("747", "Tele2-Altel");
        // Izi / Kazakhtelecom mobile.
        m.put("706", "Izi");
        return Map.copyOf(m);
    }
}
