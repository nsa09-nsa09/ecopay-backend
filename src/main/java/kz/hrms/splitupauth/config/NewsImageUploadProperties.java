package kz.hrms.splitupauth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Input-side guards for news image uploads. The bytes themselves land in S3 (see {@link
 * S3Properties}); these limits are the validation layer. All values have safe defaults — no
 * application*.properties changes are required.
 *
 * <ul>
 *   <li><code>app.uploads.news-image.max-size-bytes</code> — hard cap for the raw multipart file
 *       (default 8&nbsp;MiB; news images carry more detail than 512×512 avatars).
 *   <li><code>app.uploads.news-image.target-width</code> — long-edge target width after downscale
 *       (default 1600&nbsp;px).
 *   <li><code>app.uploads.news-image.max-decoded-dimension</code> — pixel-bomb guard, rejects
 *       images larger than this on either axis.
 * </ul>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.uploads")
public class NewsImageUploadProperties {

  private NewsImage newsImage = new NewsImage();

  @Getter
  @Setter
  public static class NewsImage {
    private long maxSizeBytes = 8L * 1024 * 1024;
    private int targetWidth = 1600;
    private int maxDecodedDimension = 8000;
  }
}
