package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.dto.DashboardLabelValueDto;
import kz.hrms.splitupauth.dto.OperatorDistributionDto;
import kz.hrms.splitupauth.dto.PopularServiceDto;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only aggregates that power the admin dashboard's distribution charts
 * (popular services / operators / currencies / categories / room statuses).
 *
 * <p>Kept separate from {@link AdminDashboardService} so the KPI block doesn't
 * grow further. Every query groups + counts in SQL so the heavy lifting stays
 * in Postgres, not the JVM.
 */
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

        return jdbc.query(sql, (rs, rowNum) -> PopularServiceDto.builder()
                .serviceId(rs.getLong("service_id"))
                .serviceName(rs.getString("service_name"))
                .roomsCount(rs.getLong("rooms_count"))
                .activeMembersCount(rs.getLong("active_members"))
                .build(), capped);
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
        // Normalise the phone to digits in SQL: strip non-digits, then look at
        // the slice immediately after the +7 country code. For +7 7XX numbers
        // the DEF-code is the 3 digits after the leading "7" (i.e. positions
        // 2..4 in the digits-only string). Foreign numbers / missing phones
        // fall into the OTHER bucket.
        String sql =
                "SELECT def_code, COUNT(*) AS cnt FROM ( "
                        + "  SELECT CASE "
                        + "    WHEN u.phone IS NULL OR u.phone = '' THEN ? "
                        + "    WHEN LENGTH(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g')) <> 11 THEN ? "
                        + "    WHEN SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 1 FOR 1) <> '7' THEN ? "
                        + "    ELSE SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 2 FOR 3) "
                        + "  END AS def_code "
                        + "  FROM users u "
                        + "  WHERE u.deleted_at IS NULL "
                        + ") buckets "
                        + "GROUP BY def_code "
                        + "ORDER BY cnt DESC";

        Map<String, Long> raw = new LinkedHashMap<>();
        jdbc.query(sql, rs -> {
            raw.put(rs.getString("def_code"), rs.getLong("cnt"));
        }, OTHER_OPERATOR_CODE, OTHER_OPERATOR_CODE, OTHER_OPERATOR_CODE);

        // Re-bucket DEF codes we don't recognize into OTHER so the FE doesn't
        // have to render dozens of one-off codes that don't map to operators.
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
    }

    // ===================== currency distribution =====================

    @Transactional(readOnly = true)
    public List<DashboardLabelValueDto> currencyDistribution() {
        String sql = "SELECT COALESCE(currency, 'KZT') AS code, COUNT(*) AS cnt "
                + "FROM rooms WHERE deleted_at IS NULL AND status = 'ACTIVE' "
                + "GROUP BY COALESCE(currency, 'KZT') "
                + "ORDER BY cnt DESC, code ASC";
        return jdbc.query(sql, (rs, n) -> DashboardLabelValueDto.builder()
                .label(rs.getString("code"))
                .value(rs.getLong("cnt"))
                .build());
    }

    // ===================== category distribution =====================

    @Transactional(readOnly = true)
    public List<DashboardLabelValueDto> categoryDistribution() {
        // LEFT JOIN keeps rooms without a category (those land under "Без категории").
        String sql = "SELECT COALESCE(c.name, ?) AS label, COUNT(*) AS cnt "
                + "FROM rooms r LEFT JOIN categories c ON c.id = r.category_id "
                + "WHERE r.deleted_at IS NULL "
                + "GROUP BY COALESCE(c.name, ?) "
                + "ORDER BY cnt DESC, label ASC";
        String uncategorised = "Без категории";
        return jdbc.query(sql, (rs, n) -> DashboardLabelValueDto.builder()
                .label(rs.getString("label"))
                .value(rs.getLong("cnt"))
                .build(), uncategorised, uncategorised);
    }

    // ===================== room status distribution =====================

    @Transactional(readOnly = true)
    public List<DashboardLabelValueDto> roomStatusDistribution() {
        String sql = "SELECT status AS label, COUNT(*) AS cnt "
                + "FROM rooms WHERE deleted_at IS NULL "
                + "GROUP BY status "
                + "ORDER BY cnt DESC, status ASC";
        return jdbc.query(sql, (rs, n) -> DashboardLabelValueDto.builder()
                .label(rs.getString("label"))
                .value(rs.getLong("cnt"))
                .build());
    }

    // ===================== operator map =====================

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
