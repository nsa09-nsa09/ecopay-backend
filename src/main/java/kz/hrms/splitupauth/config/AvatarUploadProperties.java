package kz.hrms.splitupauth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configures the avatar upload subsystem. All values have safe defaults — no
 * changes to application*.properties are required to enable uploads.
 *
 * <ul>
 *   <li><code>app.uploads.dir</code> — root directory for upload subfolders
 *   (defaults to <code>./uploads</code>, resolved relative to the working
 *   directory of the JVM process).</li>
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

    private String dir = "./uploads";

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
