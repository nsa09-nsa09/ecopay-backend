package kz.hrms.splitupauth.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Iterator;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import kz.hrms.splitupauth.config.S3Properties;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import kz.hrms.splitupauth.util.SafeImageDecoder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Shared read path and preview pipeline for served media (news + stories images). Centralizes what
 * both {@link NewsImageStorageService} and {@link StoryImageStorageService} need so the logic is
 * written once:
 *
 * <ul>
 *   <li>an in-process, byte-weighted Caffeine cache (keyed by R2 object key) so a hot image is not
 *       re-fetched from R2 on every request (R2 TTFB was 0.8–1.1&nbsp;s per original);
 *   <li>a short negative cache so a missing object is not hammered;
 *   <li>on-demand preview (thumbnail) generation for legacy objects that have none, protected by a
 *       single-flight so parallel first requests generate it exactly once;
 *   <li>a width-bounded, quality-0.8 JPEG encoder for previews.
 * </ul>
 *
 * Served filenames are UUID-hex + {@code .jpg}; {@link #servable(String)} is the single validation
 * point that keeps {@code ../} and non-jpg names out of the object key.
 */
@Component
@Slf4j
public class MediaImageStore {

  /** Served filenames are UUID hex + ".jpg"; anything else can't be one of ours. */
  private static final Pattern ALLOWED_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+\\.jpg$");

  /** Thumbnails live under "<prefix>thumb/" with the same filename as the original. */
  public static final String THUMB_SEGMENT = "thumb/";

  private final S3Client s3Client;
  private final S3Properties s3Properties;

  /** Bytes cache, bounded by total size (weigher = array length). */
  private final Cache<String, byte[]> cache;

  /** Remembers recent 404s so a missing key is not re-queried from R2 for a short while. */
  private final Cache<String, Boolean> negativeCache;

  public MediaImageStore(
      S3Client s3Client,
      S3Properties s3Properties,
      @Value("${app.media.cache-max-bytes:67108864}") long cacheMaxBytes,
      @Value("${app.media.cache-ttl-seconds:86400}") long cacheTtlSeconds,
      @Value("${app.media.negative-cache-seconds:60}") long negativeCacheSeconds) {
    this.s3Client = s3Client;
    this.s3Properties = s3Properties;
    this.cache =
        Caffeine.newBuilder()
            .maximumWeight(Math.max(1L, cacheMaxBytes))
            .weigher((String k, byte[] v) -> v.length)
            .expireAfterWrite(Duration.ofSeconds(Math.max(1L, cacheTtlSeconds)))
            .build();
    this.negativeCache =
        Caffeine.newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofSeconds(Math.max(1L, negativeCacheSeconds)))
            .build();
  }

  /** True when {@code filename} is a name we could have stored (UUID-hex + {@code .jpg}). */
  public boolean servable(String filename) {
    return filename != null && ALLOWED_FILENAME.matcher(filename).matches();
  }

  /**
   * Returns the original object's bytes via the cache, fetching from R2 at most once per key while
   * hot. A missing object is cached negatively and surfaced as {@link ResourceNotFoundException}.
   */
  public byte[] loadOriginal(String objectKey) {
    byte[] cached = cache.getIfPresent(objectKey);
    if (cached != null) {
      return cached;
    }
    if (negativeCache.getIfPresent(objectKey) != null) {
      throw new ResourceNotFoundException("Image not found");
    }
    try {
      // Caffeine's get(key, loader) is single-flight per key: concurrent callers share one fetch.
      return cache.get(objectKey, this::fetchRequiredFromR2);
    } catch (ResourceNotFoundException ex) {
      negativeCache.put(objectKey, Boolean.TRUE);
      throw ex;
    }
  }

  /**
   * Returns the preview (thumbnail) bytes for {@code thumbKey}. If the thumbnail object does not
   * yet exist (legacy image uploaded before previews), it is generated from the original at {@code
   * width}px, stored back to R2, and cached — all under a single-flight so N parallel first
   * requests for the same key generate it once. A missing original surfaces as {@link
   * ResourceNotFoundException}.
   */
  public byte[] loadThumb(String thumbKey, String originalKey, int width) {
    byte[] cached = cache.getIfPresent(thumbKey);
    if (cached != null) {
      return cached;
    }
    if (negativeCache.getIfPresent(thumbKey) != null) {
      throw new ResourceNotFoundException("Image not found");
    }
    try {
      return cache.get(thumbKey, k -> loadOrGenerateThumb(k, originalKey, width));
    } catch (ResourceNotFoundException ex) {
      negativeCache.put(thumbKey, Boolean.TRUE);
      throw ex;
    }
  }

  /**
   * Generates a preview from already-normalized original JPEG bytes and stores it at {@code
   * thumbKey}. Called at upload time so freshly stored images already have a preview. Never fails
   * the upload: a preview that cannot be built/stored is simply skipped (it can be generated lazily
   * on first read).
   */
  public void storeThumbFromOriginal(String thumbKey, byte[] originalJpeg, int width) {
    try {
      byte[] thumb = generateThumb(originalJpeg, width);
      putToR2(thumbKey, thumb);
      cache.put(thumbKey, thumb);
    } catch (RuntimeException ex) {
      log.warn("Could not pre-generate thumbnail {}: {}", thumbKey, ex.getMessage());
    }
  }

  /** Deletes an object from R2 and evicts it from the caches. Best-effort. */
  public void delete(String objectKey) {
    cache.invalidate(objectKey);
    negativeCache.invalidate(objectKey);
    try {
      s3Client.deleteObject(
          DeleteObjectRequest.builder().bucket(s3Properties.getBucket()).key(objectKey).build());
    } catch (S3Exception ex) {
      log.warn("Failed to delete object {} from bucket: {}", objectKey, ex.getMessage());
    }
  }

  // ----------------------------------------------------------------- internals

  private byte[] loadOrGenerateThumb(String thumbKey, String originalKey, int width) {
    byte[] existing = fetchOptionalFromR2(thumbKey);
    if (existing != null) {
      return existing;
    }
    // No stored preview: build it from the original (throws NotFound if the original is gone too).
    byte[] original = fetchRequiredFromR2(originalKey);
    byte[] thumb = generateThumb(original, width);
    putToR2(thumbKey, thumb);
    return thumb;
  }

  private byte[] fetchRequiredFromR2(String key) {
    byte[] bytes = fetchOptionalFromR2(key);
    if (bytes == null) {
      throw new ResourceNotFoundException("Image not found");
    }
    return bytes;
  }

  /** Returns the object's bytes, or {@code null} when it does not exist. */
  private byte[] fetchOptionalFromR2(String key) {
    try {
      return s3Client
          .getObjectAsBytes(
              GetObjectRequest.builder().bucket(s3Properties.getBucket()).key(key).build())
          .asByteArray();
    } catch (NoSuchKeyException ex) {
      return null;
    } catch (S3Exception ex) {
      log.warn("Failed to read object {} from bucket: {}", key, ex.getMessage());
      return null;
    }
  }

  private void putToR2(String key, byte[] bytes) {
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(s3Properties.getBucket())
              .key(key)
              .contentType("image/jpeg")
              .build(),
          RequestBody.fromBytes(bytes));
    } catch (S3Exception ex) {
      log.warn("Failed to store object {} in bucket: {}", key, ex.getMessage());
      throw ex;
    }
  }

  private byte[] generateThumb(byte[] originalJpeg, int width) {
    BufferedImage decoded;
    try (InputStream in = new ByteArrayInputStream(originalJpeg)) {
      decoded = SafeImageDecoder.read(in);
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot decode image for thumbnail", ex);
    }
    if (decoded == null) {
      throw new IllegalStateException("Cannot decode image for thumbnail");
    }
    BufferedImage scaled = downscale(decoded, width);
    return encodeJpeg(scaled, 0.8f);
  }

  private static BufferedImage downscale(BufferedImage src, int targetWidth) {
    int width = src.getWidth();
    int height = src.getHeight();
    double scale = Math.min(1.0, (double) targetWidth / width);
    int newW = Math.max(1, (int) Math.round(width * scale));
    int newH = Math.max(1, (int) Math.round(height * scale));

    BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = out.createGraphics();
    try {
      g.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, newW, newH);
      g.drawImage(src, 0, 0, newW, newH, null);
    } finally {
      g.dispose();
    }
    return out;
  }

  private static byte[] encodeJpeg(BufferedImage image, float quality) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IllegalStateException("No JPEG writer available");
    }
    ImageWriter writer = writers.next();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(ios);
      ImageWriteParam param = writer.getDefaultWriteParam();
      if (param.canWriteCompressed()) {
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
      }
      writer.write(null, new IIOImage(image, null, null), param);
    } catch (IOException ex) {
      throw new IllegalStateException("Failed to encode thumbnail JPEG", ex);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }
}
