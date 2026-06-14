package kz.hrms.splitupauth.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Raises multipart limits from Spring Boot's 1 MiB default to the value the
 * avatar pipeline needs. Configured here rather than in application.properties
 * so the file-handling rules live with the code that enforces them. The
 * avatar upload itself enforces a stricter per-file limit through
 * {@link AvatarUploadProperties}.
 */
@Configuration
public class MultipartConfig {

    private static final long MAX_FILE_SIZE = 6L * 1024 * 1024;
    private static final long MAX_REQUEST_SIZE = 8L * 1024 * 1024;

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        return new MultipartConfigElement("", MAX_FILE_SIZE, MAX_REQUEST_SIZE, 0);
    }
}
