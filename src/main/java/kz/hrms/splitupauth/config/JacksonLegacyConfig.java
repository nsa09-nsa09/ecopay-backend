package kz.hrms.splitupauth.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.TimeZone;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 auto-configures only `tools.jackson.databind.ObjectMapper`. Several entities and
 * services in this project still use the legacy `com.fasterxml.jackson.*` namespace (via
 * jjwt-jackson and JSONB columns), so we register a Jackson 2.x ObjectMapper bean for DI.
 *
 * <p>Timezone alignment with the Jackson 3.x mapper: every LocalDateTime is serialized as an ISO
 * offset string in Asia/Almaty (+05:00). Without this the legacy mapper would emit a zoneless ISO
 * string that the frontend can't disambiguate.
 */
@Configuration
public class JacksonLegacyConfig {

  public static final ZoneOffset ALMATY_OFFSET = ZoneOffset.ofHours(5);

  @Bean
  public ObjectMapper legacyObjectMapper() {
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(almatyLocalDateTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.setTimeZone(TimeZone.getTimeZone("Asia/Almaty"));
    return mapper;
  }

  private SimpleModule almatyLocalDateTimeModule() {
    SimpleModule module = new SimpleModule("AlmatyLocalDateTime");
    module.addSerializer(LocalDateTime.class, new AlmatyLocalDateTimeSerializer());
    return module;
  }

  /** Renders LocalDateTime as a wall-clock value pinned to +05:00 (Asia/Almaty). */
  public static final class AlmatyLocalDateTimeSerializer extends JsonSerializer<LocalDateTime> {
    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
        throws IOException {
      if (value == null) {
        gen.writeNull();
        return;
      }
      gen.writeString(value.atOffset(ALMATY_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
  }
}
