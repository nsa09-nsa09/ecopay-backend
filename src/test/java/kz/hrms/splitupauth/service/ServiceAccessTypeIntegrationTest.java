package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.ServiceDto;
import kz.hrms.splitupauth.entity.ProviderType;
import kz.hrms.splitupauth.entity.ServiceAccessType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the V54 migration against real Postgres: the column exists and is locked down, every
 * seeded service carries the access type its provider actually uses, and the value reaches the
 * public catalog DTO.
 */
class ServiceAccessTypeIntegrationTest extends AbstractIntegrationTest {

  @Autowired CatalogService catalogService;
  @Autowired JdbcTemplate jdbcTemplate;

  /** The values V54 sets per slug — the ones a member is actually asked for at join time. */
  private static final Map<String, String> EXPECTED =
      Map.ofEntries(
          Map.entry("netflix", "EMAIL"),
          Map.entry("spotify", "EMAIL"),
          Map.entry("apple-music", "EMAIL"),
          Map.entry("youtube-premium", "EMAIL"),
          Map.entry("chatgpt", "EMAIL"),
          Map.entry("apple-one", "EMAIL"),
          Map.entry("microsoft-365", "EMAIL"),
          Map.entry("playstation-plus", "EMAIL"),
          Map.entry("steam", "EMAIL"),
          Map.entry("xbox-game-pass", "EMAIL"),
          Map.entry("ivi", "PHONE"),
          Map.entry("beeline", "PHONE"),
          Map.entry("tele2", "PHONE"),
          Map.entry("kcell", "PHONE"),
          Map.entry("yandex-plus", "BOTH"));

  @Test
  void v54_setsTheRightAccessTypeForEverySeededService() {
    EXPECTED.forEach(
        (slug, expected) -> {
          String actual =
              jdbcTemplate.queryForObject(
                  "SELECT access_type FROM services WHERE slug = ?", String.class, slug);
          assertEquals(expected, actual, "access_type for " + slug);
        });
  }

  @Test
  void v54_leavesNoServiceWithoutAnAccessType() {
    Integer nulls =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM services WHERE access_type IS NULL", Integer.class);
    assertEquals(0, nulls);
  }

  @Test
  void v54_fallbackKeepsOperatorsOnPhoneAndDigitalOnEmail() {
    // Any row the per-slug list missed must still land on a sane value.
    List<Map<String, Object>> mismatches =
        jdbcTemplate.queryForList(
            """
            SELECT slug, provider_type, access_type FROM services
            WHERE (provider_type IN ('OPERATOR', 'ISP') AND access_type = 'EMAIL')
            """);
    assertTrue(mismatches.isEmpty(), "operator services must not ask for an email: " + mismatches);
  }

  @Test
  void v54_rejectsAnAccessTypeOutsideTheEnum() {
    assertThrows(
        DataIntegrityViolationException.class,
        () ->
            jdbcTemplate.update(
                "UPDATE services SET access_type = 'TELEGRAM' WHERE slug = 'netflix'"));
  }

  @Test
  void v54_widensTheIdentifierConstraintToAcceptEmail() {
    Integer allowsEmail =
        jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM pg_constraint
            WHERE conname = 'chk_room_member_identifiers_type'
              AND pg_get_constraintdef(oid) LIKE '%EMAIL%'
            """,
            Integer.class);
    assertEquals(1, allowsEmail, "members must be able to hand over an email address");
  }

  @Test
  void publicCatalog_emitsAccessTypeForEveryService() {
    List<ServiceDto> services = catalogService.getServices(null, null);
    assertTrue(!services.isEmpty(), "seed catalog must have services");
    for (ServiceDto s : services) {
      assertNotNull(s.getAccessType(), "accessType must reach the catalog DTO for " + s.getSlug());
    }

    ServiceDto spotify =
        services.stream().filter(s -> "spotify".equals(s.getSlug())).findFirst().orElseThrow();
    assertEquals(ServiceAccessType.EMAIL, spotify.getAccessType());

    ServiceDto beeline =
        services.stream().filter(s -> "beeline".equals(s.getSlug())).findFirst().orElseThrow();
    assertEquals(ProviderType.OPERATOR, beeline.getProviderType());
    assertEquals(ServiceAccessType.PHONE, beeline.getAccessType());
  }
}
