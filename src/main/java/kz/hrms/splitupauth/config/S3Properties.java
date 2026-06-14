package kz.hrms.splitupauth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the S3-compatible object storage that backs file uploads
 * (currently avatars). Works with real AWS S3 as well as S3-compatible stores
 * (MinIO, Yandex Object Storage, VK Cloud, ...) via {@link #endpoint} +
 * {@link #pathStyleAccess}.
 *
 * <p>We deliberately persist only the <b>object key</b> (e.g.
 * {@code avatars/ab12...jpg}) on the entity — never an absolute URL. The full,
 * time-limited link is generated on read via a pre-signed GET, so the same row
 * resolves correctly whether the backend runs on localhost or in production.</p>
 *
 * <p>Bind keys live under {@code app.s3.*}; credentials are injected from the
 * environment and must never be committed.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.s3")
public class S3Properties {

    /**
     * Region of the bucket. For Cloudflare R2 this is always {@code auto};
     * for real AWS S3 use the bucket region (e.g. {@code eu-central-1}).
     */
    private String region = "auto";

    /** Target bucket name. */
    private String bucket;

    /**
     * S3 endpoint. For Cloudflare R2:
     * {@code https://<ACCOUNT_ID>.r2.cloudflarestorage.com}. Leave blank only
     * for real AWS S3.
     */
    private String endpoint;

    /** Access key id. Injected from the environment — never commit a value. */
    private String accessKey;

    /** Secret access key. Injected from the environment — never commit a value. */
    private String secretKey;

    /**
     * Force path-style addressing ({@code endpoint/bucket/key} instead of
     * {@code bucket.endpoint/key}). Recommended for Cloudflare R2 and MinIO.
     */
    private boolean pathStyleAccess = true;
}
