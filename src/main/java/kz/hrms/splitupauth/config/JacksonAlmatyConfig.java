package kz.hrms.splitupauth.config;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.BeanDescription;
import tools.jackson.databind.SerializationConfig;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.BeanPropertyWriter;
import tools.jackson.databind.ser.ValueSerializerModifier;
import tools.jackson.databind.ser.std.ToStringSerializer;

/**
 * Customizes the Spring Boot 4 auto-configured Jackson 3.x JsonMapper (the mapper behind HTTP
 * message converters) in two ways:
 *
 * <ol>
 *   <li>Every LocalDateTime is serialized as an ISO offset string in Asia/Almaty (+05:00) — the
 *       suffix that removes timezone ambiguity for the frontend.
 *   <li>Every 64-bit id (a {@code Long}/{@code long} property named {@code id} or ending in {@code
 *       Id}) is serialized as a JSON <em>string</em>. The database is CockroachDB, whose {@code
 *       unique_rowid()} primary keys exceed 2^53; parsed as a JavaScript Number they lose precision,
 *       so an id round-tripped back into a URL no longer matches the stored row (the "Membership not
 *       found" class of bugs). Numeric counts/metrics are untouched because they are named {@code
 *       *Count} / are primitive metrics, never {@code *Id}.
 * </ol>
 *
 * <p>The legacy 2.x mapper in {@link JacksonLegacyConfig} is deliberately NOT given the id-string
 * treatment: it serializes JWT claims and JSONB payloads where numeric longs must stay numeric.
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

  @Bean
  public JsonMapperBuilderCustomizer bigIntIdAsStringJsonMapperCustomizer() {
    return builder -> builder.addModule(bigIntIdAsStringModule());
  }

  /**
   * Module that stringifies 64-bit id properties. Exposed statically so tests can exercise the exact
   * production behavior on a standalone mapper.
   */
  public static SimpleModule bigIntIdAsStringModule() {
    SimpleModule module = new SimpleModule("BigIntIdAsString");
    module.setSerializerModifier(new IdAsStringSerializerModifier());
    return module;
  }

  /** True for property names that denote a 64-bit entity id (id, roomId, userId, …). */
  static boolean isIdProperty(String name) {
    return name.equals("id") || name.endsWith("Id");
  }

  static final class IdAsStringSerializerModifier extends ValueSerializerModifier {
    @Override
    public List<BeanPropertyWriter> changeProperties(
        SerializationConfig config,
        BeanDescription.Supplier beanDescRef,
        List<BeanPropertyWriter> beanProperties) {
      for (BeanPropertyWriter writer : beanProperties) {
        Class<?> raw = writer.getType().getRawClass();
        if ((raw == Long.class || raw == long.class) && isIdProperty(writer.getName())) {
          writer.assignSerializer(ToStringSerializer.instance);
        }
      }
      return beanProperties;
    }
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
