package kz.hrms.splitupauth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot 4 auto-configures only `tools.jackson.databind.ObjectMapper`.
 * Several entities and services in this project still use the legacy
 * `com.fasterxml.jackson.*` namespace (via jjwt-jackson and JSONB columns),
 * so we register a Jackson 2.x ObjectMapper bean for DI.
 */
@Configuration
public class JacksonLegacyConfig {

    @Bean
    public ObjectMapper legacyObjectMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }
}
