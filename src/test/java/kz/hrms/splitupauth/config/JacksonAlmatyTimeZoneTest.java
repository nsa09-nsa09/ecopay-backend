package kz.hrms.splitupauth.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every LocalDateTime that flies out of the backend in JSON ends with the Almaty
 * offset (+05:00). Without that suffix the frontend has no way to disambiguate the wall clock and
 * historically rendered timestamps five hours behind reality.
 *
 * <p>Runs as a plain unit test (no Spring context) by exercising the legacy Jackson 2.x mapper
 * directly — the same module configuration is mirrored in the Jackson 3.x customizer used by HTTP
 * responses.
 */
class JacksonAlmatyTimeZoneTest {

  @Test
  void legacyMapperEmitsAlmatyOffsetForLocalDateTime() throws Exception {
    ObjectMapper mapper = new JacksonLegacyConfig().legacyObjectMapper();

    LocalDateTime moment = LocalDateTime.of(2026, 6, 15, 14, 30, 0);
    String json = mapper.writeValueAsString(moment);

    assertTrue(
        json.contains("+05:00"),
        "LocalDateTime JSON must declare Asia/Almaty offset, got: " + json);
    assertTrue(
        json.contains("2026-06-15T14:30:00"),
        "Wall-clock part must round-trip unchanged, got: " + json);
  }

  @Test
  void legacyMapperHandlesNullLocalDateTime() throws Exception {
    ObjectMapper mapper = new JacksonLegacyConfig().legacyObjectMapper();
    String json = mapper.writeValueAsString(new Wrapper(null));
    assertTrue(
        json.contains("\"at\":null"),
        "null LocalDateTime must serialize as JSON null, got: " + json);
  }

  private record Wrapper(LocalDateTime at) {}
}
