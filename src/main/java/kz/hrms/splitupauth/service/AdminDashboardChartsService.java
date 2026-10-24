package kz.hrms.splitupauth.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import kz.hrms.splitupauth.dto.DashboardLabelValueDto;
import kz.hrms.splitupauth.dto.OperatorDistributionDto;
import kz.hrms.splitupauth.dto.PopularServiceDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only aggregates that power the admin dashboard's distribution charts (popular services /
 * operators / currencies / categories / room statuses).
 *
 * <p>Each chart is exposed as its own endpoint and each runs its own SQL — so one failing query
 * (e.g. an upstream schema drift) cannot black out the whole dashboard. The service wraps every
 * query in {@link #safeChart(String, Supplier)} which logs the exception and returns an empty list
 * to the caller.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminDashboardChartsService {

  /**
   * KZ mobile DEF-codes → carrier. Source: official MNO numbering plan. The map is intentionally
   * small and editable — adding a new code is a one-line change here, no migration needed.
   *
   * <p>The chart is KZ-only: any non-KZ / unparseable number flows into the single {@link
   * #NON_KZ_OPERATOR_NAME} bucket (Task A). The actual country breakdown lives on the {@code
   * /country-distribution} endpoint (Task B), so we don't want to scatter foreign carriers across
   * operator buckets.
   */
  private static final Map<String, String> OPERATOR_BY_DEF_CODE = buildOperatorMap();

  private static final String OTHER_OPERATOR_CODE = "OTHER";

  /** Single bucket for everything that isn't a recognised KZ mobile DEF code. */
  private static final String NON_KZ_OPERATOR_NAME = "Другое (не KZ)";

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

    return safeChart(
        "popular-services",
        () ->
            jdbc.query(
                sql,
                (rs, rowNum) ->
                    PopularServiceDto.builder()
                        .serviceId(rs.getLong("service_id"))
                        .serviceName(rs.getString("service_name"))
                        .roomsCount(rs.getLong("rooms_count"))
                        .activeMembersCount(rs.getLong("active_members"))
                        .build(),
                capped));
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
    // Stage 1 (SQL): normalise each user's phone to digits and return the
    // 3-digit DEF block only when the number is KZ mobile (11 digits,
    // first digit '7', second digit '7'). Everything else collapses into
    // the OTHER bucket, which Stage 2 (Java) renders as a single
    // "Другое (не KZ)" bar — never split per-code.
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
            // Only +7 7XX is KZ mobile. +7 9XX is Russian mobile — must NOT be
            // grouped under a KZ operator, and is folded into OTHER here so the
            // country chart can pick it up cleanly downstream.
            + "    WHEN SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 2 FOR 1) <> '7' THEN 'OTHER' "
            + "    ELSE SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 2 FOR 3) "
            + "  END AS def_code "
            + "  FROM users u "
            + "  WHERE u.deleted_at IS NULL "
            + ") buckets "
            + "GROUP BY def_code "
            + "ORDER BY cnt DESC";

    return safeChart(
        "operator-distribution",
        () -> {
          Map<String, Long> raw = new LinkedHashMap<>();
          // Explicit RowCallbackHandler — without it the lambda is ambiguous
          // with ResultSetExtractor on Spring 7's overloaded JdbcTemplate.
          org.springframework.jdbc.core.RowCallbackHandler handler =
              rs -> raw.put(rs.getString("def_code"), rs.getLong("cnt"));
          jdbc.query(sql, handler);

          // Stage 2: roll every DEF code up to its operator NAME. Multiple
          // codes for the same carrier (e.g. 701 + 778 → "Kcell/Activ")
          // therefore appear as ONE bar, not several — that was the bug
          // the chart had before this rewrite. Preserves insertion order
          // from raw so when counts tie, the response remains stable.
          Map<String, Long> byOperator = new LinkedHashMap<>();
          long nonKzCount = raw.getOrDefault(OTHER_OPERATOR_CODE, 0L);
          for (Map.Entry<String, Long> e : raw.entrySet()) {
            String code = e.getKey();
            if (OTHER_OPERATOR_CODE.equals(code)) continue;
            String name = OPERATOR_BY_DEF_CODE.get(code);
            if (name == null) {
              // KZ DEF block that isn't in our map → fold into the
              // single non-KZ-ish bucket so we never emit a dozen
              // anonymous 3-digit bars.
              nonKzCount += e.getValue();
              continue;
            }
            byOperator.merge(name, e.getValue(), Long::sum);
          }

          List<OperatorDistributionDto> result = new ArrayList<>(byOperator.size() + 1);
          for (Map.Entry<String, Long> e : byOperator.entrySet()) {
            result.add(
                OperatorDistributionDto.builder()
                    // Code stays null in the merged shape: a single bar
                    // can cover multiple DEF codes, so emitting one would
                    // be misleading. Frontend keys on operatorName.
                    .code(null)
                    .operatorName(e.getKey())
                    .count(e.getValue())
                    .build());
          }
          // Exactly one "Другое" bar — never duplicated.
          if (nonKzCount > 0) {
            result.add(
                OperatorDistributionDto.builder()
                    .code(OTHER_OPERATOR_CODE)
                    .operatorName(NON_KZ_OPERATOR_NAME)
                    .count(nonKzCount)
                    .build());
          }
          // Sort by count DESC; tie-break by name to stay deterministic.
          result.sort(
              (a, b) -> {
                int c = Long.compare(b.getCount(), a.getCount());
                return c != 0 ? c : a.getOperatorName().compareTo(b.getOperatorName());
              });
          return result;
        });
  }

  // ===================== currency distribution =====================

  @Transactional(readOnly = true)
  public List<DashboardLabelValueDto> currencyDistribution() {
    String sql =
        "SELECT COALESCE(currency, 'KZT') AS code, COUNT(*) AS cnt "
            + "FROM rooms WHERE deleted_at IS NULL AND status = 'ACTIVE' "
            + "GROUP BY COALESCE(currency, 'KZT') "
            + "ORDER BY cnt DESC, code ASC";
    return safeChart(
        "currency-distribution",
        () ->
            jdbc.query(
                sql,
                (rs, n) ->
                    DashboardLabelValueDto.builder()
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
    String sql =
        "SELECT COALESCE(c.name, '"
            + escapeLiteral(UNCATEGORISED_LABEL)
            + "') AS label, COUNT(*) AS cnt "
            + "FROM rooms r LEFT JOIN categories c ON c.id = r.category_id "
            + "WHERE r.deleted_at IS NULL "
            + "GROUP BY c.name "
            + "ORDER BY cnt DESC, label ASC";
    return safeChart(
        "category-distribution",
        () ->
            jdbc.query(
                sql,
                (rs, n) ->
                    DashboardLabelValueDto.builder()
                        .label(rs.getString("label"))
                        .value(rs.getLong("cnt"))
                        .build()));
  }

  // ===================== country distribution =====================

  @Transactional(readOnly = true)
  public List<DashboardLabelValueDto> countryDistribution() {
    // Country is derived from the user's phone-number prefix on the
    // normalised (digits-only) representation. The CASE ladder is kept in
    // SQL because Postgres can aggregate the bucketed value in a single
    // pass — pulling all phones into Java would be wasteful, and the
    // small set of prefixes we recognise doesn't justify a join table.
    //
    // Prefix policy:
    //   - +7 XYY where Y is mobile: '7' (X=7) → Казахстан, '9' (X=9) → Россия.
    //   - Other +7 prefixes (landlines, special) → "Россия/Казахстан (+7)"
    //     — both countries share country code 7, so without more digits
    //     we can't disambiguate; surfacing them as one bar is honest.
    //   - Other prefixes use a small lookup of CIS + common destinations.
    //   - Everything else → "Другое".
    //
    // Normalisation step also strips a leading '8' (legacy KZ/RU national
    // prefix the keypad rewrites to '+7') so a "8 700 …" number is
    // treated identically to "+7 700 …".
    String sql =
        "WITH normalised AS ( "
            + "  SELECT CASE "
            + "    WHEN u.phone IS NULL OR u.phone = '' THEN '' "
            + "    WHEN SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 1 FOR 1) = '8' "
            + "         AND LENGTH(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g')) = 11 "
            + "      THEN '7' || SUBSTRING(REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') FROM 2) "
            + "    ELSE REGEXP_REPLACE(u.phone, '[^0-9]', '', 'g') "
            + "  END AS digits "
            + "  FROM users u WHERE u.deleted_at IS NULL "
            + "), bucketed AS ( "
            + "  SELECT CASE "
            + "    WHEN digits = '' THEN 'Другое' "
            // +7 XYZ — disambiguate KZ vs RU using the second digit of the
            // national number (i.e. third digit overall). +7 7XX = KZ mobile,
            // +7 9XX = RU mobile; other +7 prefixes are shared by KZ/RU
            // numbering plans and we surface them under a joint label.
            + "    WHEN LENGTH(digits) = 11 AND SUBSTRING(digits FROM 1 FOR 1) = '7' "
            + "         AND SUBSTRING(digits FROM 2 FOR 1) = '7' THEN 'Казахстан' "
            + "    WHEN LENGTH(digits) = 11 AND SUBSTRING(digits FROM 1 FOR 1) = '7' "
            + "         AND SUBSTRING(digits FROM 2 FOR 1) = '9' THEN 'Россия' "
            + "    WHEN LENGTH(digits) = 11 AND SUBSTRING(digits FROM 1 FOR 1) = '7' "
            + "         THEN 'Россия/Казахстан (+7)' "
            // Multi-digit country codes — order longest prefix first so '998'
            // doesn't get shadowed by a future '9' rule.
            + "    WHEN SUBSTRING(digits FROM 1 FOR 3) = '998' THEN 'Узбекистан' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 3) = '996' THEN 'Кыргызстан' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 3) = '992' THEN 'Таджикистан' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 3) = '375' THEN 'Беларусь' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 3) = '374' THEN 'Армения' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 3) = '994' THEN 'Азербайджан' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 3) = '995' THEN 'Грузия' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 2) = '90' THEN 'Турция' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 2) = '84' THEN 'Вьетнам' "
            + "    WHEN SUBSTRING(digits FROM 1 FOR 2) = '86' THEN 'Китай' "
            + "    ELSE 'Другое' "
            + "  END AS country "
            + "  FROM normalised "
            + ") "
            + "SELECT country AS label, COUNT(*) AS cnt "
            + "FROM bucketed "
            + "GROUP BY country "
            + "ORDER BY cnt DESC, country ASC";

    return safeChart(
        "country-distribution",
        () ->
            jdbc.query(
                sql,
                (rs, n) ->
                    DashboardLabelValueDto.builder()
                        .label(rs.getString("label"))
                        .value(rs.getLong("cnt"))
                        .build()));
  }

  // ===================== room status distribution =====================

  @Transactional(readOnly = true)
  public List<DashboardLabelValueDto> roomStatusDistribution() {
    String sql =
        "SELECT status AS label, COUNT(*) AS cnt "
            + "FROM rooms WHERE deleted_at IS NULL "
            + "GROUP BY status "
            + "ORDER BY cnt DESC, status ASC";
    return safeChart(
        "room-status-distribution",
        () ->
            jdbc.query(
                sql,
                (rs, n) ->
                    DashboardLabelValueDto.builder()
                        .label(rs.getString("label"))
                        .value(rs.getLong("cnt"))
                        .build()));
  }

  // ===================== helpers =====================

  /**
   * Isolate one chart from another. A schema drift in (say) the operator SQL must not blank out the
   * popular-services chart that the FE renders in the same row. The caller still sees a 200 with an
   * empty list; the cause is in the server log so it can be diagnosed without paging the admin
   * user.
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

  /**
   * Escape single quotes for a SQL string literal. UNCATEGORISED_LABEL has none, but guard anyway.
   */
  private String escapeLiteral(String value) {
    return value.replace("'", "''");
  }

  private static Map<String, String> buildOperatorMap() {
    // Authoritative KZ mobile DEF-block → operator name map. Numbers may
    // be split across DEF codes for one carrier (e.g. Kcell/Activ holds
    // 701, 702, 775, 778) — the aggregation in operatorDistribution()
    // merges them into a single named bar.
    Map<String, String> m = new LinkedHashMap<>();
    // Altel — Mobile Telecom-Service LLP (Tele2-Altel merger; Altel
    // brand kept on 700/708 by historical assignment).
    m.put("700", "Altel");
    m.put("708", "Altel");
    // Kcell / Activ — Kcell JSC (both brands).
    m.put("701", "Kcell/Activ");
    m.put("702", "Kcell/Activ");
    m.put("775", "Kcell/Activ");
    m.put("778", "Kcell/Activ");
    // Beeline KZ — KaR-Tel LLP.
    m.put("705", "Beeline");
    m.put("771", "Beeline");
    m.put("776", "Beeline");
    m.put("777", "Beeline");
    // IZI — Kazakhtelecom mobile virtual brand.
    m.put("706", "IZI");
    // Tele2 — Mobile Telecom-Service LLP (other half of the merger).
    m.put("707", "Tele2");
    m.put("747", "Tele2");
    // Kazakhtelecom landlines / fixed (rarely seen on mobile rows but
    // we accept them rather than scattering into OTHER).
    m.put("750", "Kazakhtelecom");
    m.put("751", "Kazakhtelecom");
    m.put("760", "Kazakhtelecom");
    m.put("761", "Kazakhtelecom");
    // Niche/legacy operators — kept named so they don't pollute OTHER.
    m.put("762", "Nursat");
    m.put("763", "Arna");
    m.put("764", "2 Day Telecom");
    // Reserved KZ blocks not currently allocated to a specific carrier.
    m.put("703", "Резерв");
    m.put("704", "Резерв");
    m.put("709", "Резерв");
    return Map.copyOf(m);
  }
}
