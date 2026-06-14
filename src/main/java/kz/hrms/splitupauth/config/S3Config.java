package kz.hrms.splitupauth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Wires the S3 client from {@link S3Properties}. Works against real AWS S3 and
 * S3-compatible stores (Cloudflare R2, MinIO, ...). Avatars are streamed through
 * the backend rather than served via pre-signed URLs, so only the sync client is
 * needed. Building the bean performs no network I/O, so the app starts even
 * before the bucket exists or credentials are filled in — failures surface only
 * on the first upload/read.
 */
@Configuration
@EnableConfigurationProperties(S3Properties.class)
public class S3Config {

    @Bean
    public S3Client s3Client(S3Properties props) {
        var builder = S3Client.builder()
                .region(Region.of(props.getRegion()))
                .credentialsProvider(credentials(props))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(props.isPathStyleAccess())
                        .build());
        if (hasText(props.getEndpoint())) {
            builder.endpointOverride(URI.create(props.getEndpoint().trim()));
        }
        return builder.build();
    }

    private static StaticCredentialsProvider credentials(S3Properties props) {
        String accessKey = props.getAccessKey() == null ? "" : props.getAccessKey();
        String secretKey = props.getSecretKey() == null ? "" : props.getSecretKey();
        return StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
