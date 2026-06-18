package kz.hrms.splitupauth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Input-side guards for service-logo uploads. Mirrors
 * {@link NewsImageUploadProperties}; logos sit on a smaller target width since
 * the FE only renders them at icon size.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.uploads")
public class ServiceLogoUploadProperties {

    private ServiceLogo serviceLogo = new ServiceLogo();

    @Getter
    @Setter
    public static class ServiceLogo {
        private long maxSizeBytes = 4L * 1024 * 1024;
        private int targetWidth = 512;
        private int maxDecodedDimension = 6000;
    }
}
