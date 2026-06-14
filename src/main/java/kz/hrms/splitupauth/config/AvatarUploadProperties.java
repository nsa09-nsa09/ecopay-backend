package kz.hrms.splitupauth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures avatar validation/normalisation limits. The bytes themselves are
 * stored in S3 (see {@link S3Properties}); these are the input-side guards. All
 * values have safe defaults — no changes to application*.properties are required.
 *
 * <ul>
 *   <li><code>app.uploads.avatar.max-size-bytes</code> — hard cap for the
 *   raw multipart file in bytes (default 5&nbsp;MiB).</li>
 *   <li><code>app.uploads.avatar.target-size</code> — square edge length the
 *   re-encoded image is downscaled to (default 512&nbsp;px).</li>
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.uploads")
public class AvatarUploadProperties {

    private Avatar avatar = new Avatar();

    @Getter
    @Setter
    public static class Avatar {
        private long maxSizeBytes = 5L * 1024 * 1024;
        private int targetSize = 512;
        /** Reject decoded images larger than this on each axis (pixel-bomb guard). */
        private int maxDecodedDimension = 6000;
    }
}
