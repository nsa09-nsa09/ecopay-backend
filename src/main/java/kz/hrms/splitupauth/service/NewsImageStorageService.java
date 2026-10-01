package kz.hrms.splitupauth.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import kz.hrms.splitupauth.config.NewsImageUploadProperties;
import kz.hrms.splitupauth.config.S3Properties;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Handles the news-image upload pipeline end-to-end (mirrors {@link AvatarStorageService}): input
 * validation (size, extension, MIME magic bytes), decoding, downscaling to a target width,
 * re-encoding as JPEG (which strips any embedded payload), and persistence to S3-compatible object
 * storage (Cloudflare R2 in this deployment).
 *
 * <p>Only the <b>object key</b> (e.g. {@code news/ab12...jpg}) is returned and persisted on {@code
 * News.imageKey} — never an absolute URL. Images are served <b>through the backend host</b> ({@link
 * #publicUrl(String)} + {@link #loadImageBytes(String)}) so R2 credentials and the bucket endpoint
 * never leave the backend, exactly like avatars.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NewsImageStorageService {

  /** Object-key "folder" for news images within the bucket. */
  private static final String NEWS_PREFIX = "news/";

  /** Public path the backend serves news images from; pairs with {@link #NEWS_PREFIX}. */
  public static final String NEWS_URL_PATH = "/api/v1/news/images/";

  /** Filenames are UUID hex + ".jpg"; anything else can't be one of ours. */
  private static final Pattern ALLOWED_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+\\.jpg$");

  private final NewsImageUploadProperties properties;
  private final S3Properties s3Properties;
  private final S3Client s3Client;

  /** Fallback base URL when an image link is built outside an HTTP request. */
  @Value("${app.base-url:http://localhost:8080}")
  private String baseUrl;

  /**
   * Validates the upload, normalises it to a width-bounded JPEG and stores it in the bucket.
   * Returns the object key persisted on {@code News.imageKey}.
   */
  public String store(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new InvalidRequestException("Файл не передан");
    }
    if (file.getSize() > properties.getNewsImage().getMaxSizeBytes()) {
      throw new InvalidRequestException(
          "Файл превышает лимит "
              + (properties.getNewsImage().getMaxSizeBytes() / (1024 * 1024))
              + " МБ");
    }

    String original = file.getOriginalFilename();
    String extension = sniffExtension(original);
    if (!isExtensionAllowed(extension)) {
      throw new InvalidRequestException("Разрешены только файлы png, jpg, jpeg");
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw new InvalidRequestException("Не удалось прочитать файл");
    }

    if (!magicBytesMatch(bytes)) {
      throw new InvalidRequestException("Файл не похож на изображение (неверная сигнатура)");
    }

    BufferedImage decoded;
    try (InputStream in = new ByteArrayInputStream(bytes)) {
      decoded = ImageIO.read(in);
    } catch (IOException ex) {
      throw new InvalidRequestException("Не удалось декодировать изображение");
    }
    if (decoded == null) {
      throw new InvalidRequestException("Не удалось декодировать изображение");
    }

    int maxDim = properties.getNewsImage().getMaxDecodedDimension();
    if (decoded.getWidth() > maxDim || decoded.getHeight() > maxDim) {
      throw new InvalidRequestException(
          "Изображение слишком большое (" + decoded.getWidth() + "x" + decoded.getHeight() + ")");
    }

    BufferedImage normalized = downscale(decoded, properties.getNewsImage().getTargetWidth());
    byte[] jpeg = encodeJpeg(normalized);

    String key = NEWS_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".jpg";
    try {
      s3Client.putObject(
          PutObjectRequest.builder()
              .bucket(s3Properties.getBucket())
              .key(key)
              .contentType("image/jpeg")
              .build(),
          RequestBody.fromBytes(jpeg));
    } catch (S3Exception ex) {
      log.error(
          "Failed to upload news image to bucket {} key {}", s3Properties.getBucket(), key, ex);
      throw new InvalidRequestException("Не удалось сохранить файл в хранилище");
    }

    return key;
  }

  /**
   * Turns a stored object key into a viewable link served by this backend ({@code
   * {host}/api/v1/news/images/<file>.jpg}). Non-managed values (null/blank/legacy) yield {@code
   * null}.
   */
  public String publicUrl(String storedValue) {
    if (!isManaged(storedValue)) {
      return null;
    }
    String filename = storedValue.substring(NEWS_PREFIX.length());
    return resolveHost() + NEWS_URL_PATH + filename;
  }

  /**
   * Streams a stored image's bytes from the bucket for the public serving endpoint. The filename is
   * validated so it can only resolve to a key under our news prefix. Throws {@link
   * ResourceNotFoundException} when missing.
   */
  public byte[] loadImageBytes(String filename) {
    if (filename == null || !ALLOWED_FILENAME.matcher(filename).matches()) {
      throw new ResourceNotFoundException("News image not found");
    }
    String key = NEWS_PREFIX + filename;
    try {
      return s3Client
          .getObjectAsBytes(
              GetObjectRequest.builder().bucket(s3Properties.getBucket()).key(key).build())
          .asByteArray();
    } catch (NoSuchKeyException ex) {
      throw new ResourceNotFoundException("News image not found");
    } catch (S3Exception ex) {
      log.warn(
          "Failed to read news image {} from bucket {}: {}",
          key,
          s3Properties.getBucket(),
          ex.getMessage());
      throw new ResourceNotFoundException("News image not found");
    }
  }

  /**
   * Deletes a previously stored news image from the bucket. Non-managed values (null, or leftover
   * legacy strings) are ignored.
   */
  public void deleteIfManaged(String storedValue) {
    if (!isManaged(storedValue)) {
      return;
    }
    try {
      s3Client.deleteObject(
          DeleteObjectRequest.builder().bucket(s3Properties.getBucket()).key(storedValue).build());
    } catch (S3Exception ex) {
      log.warn(
          "Failed to delete news image {} from bucket {}: {}",
          storedValue,
          s3Properties.getBucket(),
          ex.getMessage());
    }
  }

  private boolean isManaged(String storedValue) {
    return storedValue != null && !storedValue.isBlank() && storedValue.startsWith(NEWS_PREFIX);
  }

  private String resolveHost() {
    if (RequestContextHolder.getRequestAttributes() != null) {
      try {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
            .build()
            .toUriString()
            .replaceAll("/+$", "");
      } catch (IllegalStateException ignored) {
        // No usable request — fall through to the configured base URL.
      }
    }
    return (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
  }

  private byte[] encodeJpeg(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      if (!ImageIO.write(image, "jpg", out)) {
        throw new IOException("ImageIO returned no JPEG writer");
      }
    } catch (IOException ex) {
      log.error("Failed to encode news image JPEG", ex);
      throw new InvalidRequestException("Не удалось обработать изображение");
    }
    return out.toByteArray();
  }

  private String sniffExtension(String filename) {
    if (filename == null) return "";
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase();
  }

  private boolean isExtensionAllowed(String ext) {
    return "png".equals(ext) || "jpg".equals(ext) || "jpeg".equals(ext);
  }

  private boolean magicBytesMatch(byte[] bytes) {
    if (bytes.length < 4) return false;
    if ((bytes[0] & 0xFF) == 0x89
        && (bytes[1] & 0xFF) == 0x50
        && (bytes[2] & 0xFF) == 0x4E
        && (bytes[3] & 0xFF) == 0x47) {
      return true;
    }
    if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
      return true;
    }
    return false;
  }

  /**
   * Width-bounded downscale: keeps the aspect ratio, shrinks to {@code targetWidth} on the long
   * edge when necessary, leaves anything smaller untouched.
   */
  private BufferedImage downscale(BufferedImage src, int targetWidth) {
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
      // Flatten transparency on a white background (TYPE_INT_RGB has no alpha).
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, newW, newH);
      g.drawImage(src, 0, 0, newW, newH, null);
    } finally {
      g.dispose();
    }
    return out;
  }
}
