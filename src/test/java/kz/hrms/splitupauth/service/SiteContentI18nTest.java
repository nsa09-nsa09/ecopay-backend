package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.AbstractIntegrationTest;
import kz.hrms.splitupauth.dto.SiteContentDto;
import kz.hrms.splitupauth.dto.UpdateSiteContentRequest;
import kz.hrms.splitupauth.entity.Role;
import kz.hrms.splitupauth.entity.User;
import kz.hrms.splitupauth.entity.UserStatus;
import kz.hrms.splitupauth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Covers the tri-lingual About Us extension (V30): the seed row backfills existing copy into *_ru,
 * admin can write all three languages in one PUT, and {@code ?lang=} on the public endpoint can
 * collapse the chosen language back into the legacy single-field shape without dropping the other
 * locales.
 */
class SiteContentI18nTest extends AbstractIntegrationTest {

  @Autowired SiteContentService service;
  @Autowired UserRepository userRepository;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  private static final AtomicInteger SEQ = new AtomicInteger();

  private User admin() {
    int n = SEQ.incrementAndGet();
    return userRepository.save(
        User.builder()
            .email("sc_admin_" + n + "_" + System.nanoTime() + "@t.kz")
            .password("x")
            .displayName("SC Admin " + n)
            .role(Role.ADMIN)
            .status(UserStatus.ACTIVE)
            .build());
  }

  @Test
  void v30Migration_addsTriLingualColumns() {
    // We can't assert against the row's content (other tests share this
    // testcontainer and may mutate the singleton row). Instead confirm
    // that the V30 migration applied: the columns exist and are reachable
    // from the JPA layer, which means our entity update is consistent
    // with the actual schema. ddl-auto=validate also implicitly enforces
    // this at context start — if the columns were missing the suite
    // would fail to boot.
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT title_kz, title_ru, title_en, "
                + "       mission_kz, mission_ru, mission_en, "
                + "       description_kz, description_ru, description_en "
                + "FROM site_content WHERE id = 1");
    // Keys are returned lower-case by the JDBC driver. All nine slots must be present.
    for (String key :
        new String[] {
          "title_kz", "title_ru", "title_en",
          "mission_kz", "mission_ru", "mission_en",
          "description_kz", "description_ru", "description_en"
        }) {
      assertTrue(row.containsKey(key), "V30 column missing: " + key);
    }
    // The migration backfills *_ru from the legacy columns on insert, so
    // after migration the RU title is non-null even if no admin has saved.
    assertNotNull(
        row.get("title_ru"), "V30 backfill should populate title_ru from the legacy title");
  }

  @Test
  void updateAllThreeLanguages_persistsAndReturns() {
    User adminUser = admin();
    UpdateSiteContentRequest req = new UpdateSiteContentRequest();
    req.setCompanyName("EcoPay");
    req.setTitle("О EcoPay");
    req.setMission("Mission RU mirror");
    req.setDescription("Description RU mirror");

    req.setTitleRu("О EcoPay");
    req.setMissionRu("Делим подписки");
    req.setDescriptionRu("RU описание");

    req.setTitleKz("EcoPay туралы");
    req.setMissionKz("Жазылымдарды бөлеміз");
    req.setDescriptionKz("KZ сипаттамасы");

    req.setTitleEn("About EcoPay");
    req.setMissionEn("Split your subscriptions");
    req.setDescriptionEn("EN description");

    SiteContentDto updated = service.updateAbout(adminUser, req, new MockHttpServletRequest());

    assertEquals("EcoPay туралы", updated.getTitleKz());
    assertEquals("О EcoPay", updated.getTitleRu());
    assertEquals("About EcoPay", updated.getTitleEn());
    assertEquals("Жазылымдарды бөлеміз", updated.getMissionKz());
    assertEquals("Split your subscriptions", updated.getMissionEn());

    // The legacy fields should still hold the (RU) values for backward compat.
    assertEquals("О EcoPay", updated.getTitle());

    // Re-read confirms the row was persisted.
    SiteContentDto fresh = service.getAbout();
    assertEquals("EcoPay туралы", fresh.getTitleKz());
    assertEquals("EN description", fresh.getDescriptionEn());
  }

  @Test
  void wireContract_isSnakeCaseForPerLanguageFields_andRoundTripsThroughService() throws Exception {
    // The admin About editor speaks snake_case. Verify both directions of
    // the JSON contract here so a future Jackson config tweak doesn't
    // silently regress to camelCase and drop kz/en again. We reuse the
    // Spring-configured ObjectMapper so JSR310 / module setup matches
    // what the controller layer actually serializes with.

    String json =
        "{"
            + "\"companyName\":\"EcoPay\","
            + "\"title\":\"О EcoPay\","
            + "\"mission\":\"Mission RU mirror\","
            + "\"description\":\"Description RU mirror\","
            + "\"title_kz\":\"EcoPay туралы\","
            + "\"title_ru\":\"О EcoPay\","
            + "\"title_en\":\"About EcoPay\","
            + "\"mission_kz\":\"KZ миссия\","
            + "\"mission_ru\":\"RU миссия\","
            + "\"mission_en\":\"EN mission\","
            + "\"description_kz\":\"KZ сипаттамасы\","
            + "\"description_ru\":\"RU описание\","
            + "\"description_en\":\"EN description\""
            + "}";

    UpdateSiteContentRequest req = mapper.readValue(json, UpdateSiteContentRequest.class);
    assertEquals("EcoPay туралы", req.getTitleKz());
    assertEquals("About EcoPay", req.getTitleEn());
    assertEquals("KZ миссия", req.getMissionKz());
    assertEquals("EN mission", req.getMissionEn());
    assertEquals("KZ сипаттамасы", req.getDescriptionKz());
    assertEquals("EN description", req.getDescriptionEn());

    // Persist via the real service to prove the request DTO and the
    // service-layer setters are wired together: the kz/en values must
    // survive a subsequent getAbout() unchanged.
    User adminUser = admin();
    service.updateAbout(adminUser, req, new MockHttpServletRequest());
    SiteContentDto fresh = service.getAbout();
    assertEquals("EcoPay туралы", fresh.getTitleKz());
    assertEquals("About EcoPay", fresh.getTitleEn());
    assertEquals("EN description", fresh.getDescriptionEn());

    // Response side: snake_case keys must appear on the wire, camelCase
    // ones must not — that's what the frontend reads.
    String responseJson = mapper.writeValueAsString(fresh);
    assertTrue(
        responseJson.contains("\"title_kz\""), "expected title_kz in response: " + responseJson);
    assertTrue(
        responseJson.contains("\"title_en\""), "expected title_en in response: " + responseJson);
    assertTrue(responseJson.contains("\"mission_kz\""), "expected mission_kz in response");
    assertTrue(responseJson.contains("\"description_en\""), "expected description_en in response");
    // Legacy flat fields must stay camelCase.
    assertTrue(
        responseJson.contains("\"companyName\""), "legacy companyName must remain camelCase");
  }

  @Test
  void preferLanguage_collapsesChosenLocaleIntoLegacySlots() {
    User adminUser = admin();
    UpdateSiteContentRequest req = new UpdateSiteContentRequest();
    req.setCompanyName("EcoPay");
    req.setTitle("ru-title");
    req.setMission("ru-mission");
    req.setDescription("ru-description");
    req.setTitleKz("kz-title");
    req.setMissionKz("kz-mission");
    req.setDescriptionKz("kz-description");
    req.setTitleEn("en-title");
    req.setMissionEn("en-mission");
    req.setDescriptionEn("en-description");
    service.updateAbout(adminUser, req, new MockHttpServletRequest());

    SiteContentDto kzView = service.getAbout().preferLanguage("kz");
    assertEquals("kz-title", kzView.getTitle());
    assertEquals("kz-mission", kzView.getMission());
    assertEquals("kz-description", kzView.getDescription());
    // The other languages must still be present so the FE can re-render
    // without another request when the user switches the UI language.
    assertEquals("en-title", kzView.getTitleEn());
    assertEquals("ru-title", kzView.getTitleRu());
  }
}
