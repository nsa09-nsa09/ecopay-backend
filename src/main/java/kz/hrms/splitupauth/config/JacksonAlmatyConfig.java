package kz.hrms.splitupauth.config;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * Wires the Spring Boot 4 auto-configured Jackson 3.x JsonMapper (used by HTTP message converters)
 * so every LocalDateTime is serialized as an ISO offset string in Asia/Almaty. The +05:00 suffix is
 * what removes timezone ambiguity for the frontend.
 *
 * <p>The legacy 2.x mapper in {@link JacksonLegacyConfig} is configured the same way so internal
 * code paths that use {@code ObjectMapper objectMapper} stay consistent with the HTTP layer.
 */
@Configuration
public class JacksonAlmatyConfig {

  public static final ZoneOffset ALMATY_OFFSET = ZoneOffset.ofHours(5);

  @Bean
  public JsonMapperBuilderCustomizer almatyTimeJsonMapperCustomizer() {
    SimpleModule module = new SimpleModule("AlmatyLocalDateTime3");
    module.addSerializer(LocalDateTime.class, new AlmatyLocalDateTimeSerializer3());
    return builder -> builder.addModule(module);
  }

  public static final class AlmatyLocalDateTimeSerializer3 extends ValueSerializer<LocalDateTime> {
    @Override
    public void serialize(LocalDateTime value, JsonGenerator gen, SerializationContext ctx) {
      if (value == null) {
        gen.writeNull();
        return;
      }
      gen.writeString(value.atOffset(ALMATY_OFFSET).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }
  }
}
