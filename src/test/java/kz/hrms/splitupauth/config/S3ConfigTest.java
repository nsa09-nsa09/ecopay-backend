package kz.hrms.splitupauth.config;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class S3ConfigTest {

    private final S3Config config = new S3Config();

    /**
     * Regression guard: without S3 credentials (CI, local dev without R2) the
     * client bean must still build. {@code AwsBasicCredentials} rejects blank
     * keys, so blank values are swapped for placeholders in {@link S3Config} —
     * otherwise bean creation would fail and bring down the whole context.
     */
    @Test
    void s3Client_buildsWithBlankCredentials() {
        S3Properties props = new S3Properties();
        // region defaults to "auto"; access/secret keys are null/blank here.
        S3Client client = assertDoesNotThrow(() -> config.s3Client(props));
        assertNotNull(client);
    }

    @Test
    void s3Client_buildsWithCustomEndpointAndCredentials() {
        S3Properties props = new S3Properties();
        props.setEndpoint("https://example.r2.cloudflarestorage.com");
        props.setAccessKey("ak");
        props.setSecretKey("sk");
        S3Client client = assertDoesNotThrow(() -> config.s3Client(props));
        assertNotNull(client);
    }
}
