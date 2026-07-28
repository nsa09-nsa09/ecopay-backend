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

@Service
@RequiredArgsConstructor
@Slf4j
public class StoryImageStorageService {

  private static final String STORY_PREFIX = "stories/";
  private static final String STORY_URL_PATH = "/api/v1/stories/images/";
  private static final Pattern ALLOWED_FILENAME = Pattern.compile("^[a-zA-Z0-9._-]+\\.jpg$");

  private final NewsImageUploadProperties properties;
  private final S3Properties s3Properties;
  private final S3Client s3Client;

  @Value("${app.base-url:http://localhost:8080}")
  private String baseUrl;

  public String store(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new InvalidRequestException("File is required");
    }
    if (file.getSize() > properties.getNewsImage().getMaxSizeBytes()) {
      throw new InvalidRequestException("File is too large");
    }

    String extension = sniffExtension(file.getOriginalFilename());
    if (!isExtensionAllowed(extension)) {
      throw new InvalidRequestException("Only PNG and JPG files are supported");
    }

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException ex) {
      throw new InvalidRequestException("Failed to read file");
    }

    if (!magicBytesMatch(bytes)) {
      throw new InvalidRequestException("File is not a valid image");
    }

    BufferedImage decoded;
    try (InputStream in = new ByteArrayInputStream(bytes)) {
      decoded = ImageIO.read(in);
    } catch (IOException ex) {
      throw new InvalidRequestException("Failed to decode image");
    }
    if (decoded == null) {
      throw new InvalidRequestException("Failed to decode image");
    }

    int maxDim = properties.getNewsImage().getMaxDecodedDimension();
    if (decoded.getWidth() > maxDim || decoded.getHeight() > maxDim) {
      throw new InvalidRequestException("Image dimensions are too large");
    }

    BufferedImage normalized = downscale(decoded, properties.getNewsImage().getTargetWidth());
    byte[] jpeg = encodeJpeg(normalized);
    String key = STORY_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".jpg";

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
          "Failed to upload story image to bucket {} key {}",
          s3Properties.getBucket(),
          key,
          ex);
      throw new InvalidRequestException("Failed to store image");
    }

    return key;
  }

  public String publicUrl(String storedValue) {
    if (!isManaged(storedValue)) {
      return null;
    }
    String filename = storedValue.substring(STORY_PREFIX.length());
    return resolveHost() + STORY_URL_PATH + filename;
  }

  public byte[] loadImageBytes(String filename) {
    if (filename == null || !ALLOWED_FILENAME.matcher(filename).matches()) {
      throw new ResourceNotFoundException("Story image not found");
    }
    String key = STORY_PREFIX + filename;
    try {
      return s3Client
          .getObjectAsBytes(
              GetObjectRequest.builder().bucket(s3Properties.getBucket()).key(key).build())
          .asByteArray();
    } catch (NoSuchKeyException ex) {
      throw new ResourceNotFoundException("Story image not found");
    } catch (S3Exception ex) {
      log.warn(
          "Failed to read story image {} from bucket {}: {}",
          key,
          s3Properties.getBucket(),
          ex.getMessage());
      throw new ResourceNotFoundException("Story image not found");
    }
  }

  public void deleteIfManaged(String storedValue) {
    if (!isManaged(storedValue)) {
      return;
    }
    try {
      s3Client.deleteObject(
          DeleteObjectRequest.builder().bucket(s3Properties.getBucket()).key(storedValue).build());
    } catch (S3Exception ex) {
      log.warn(
          "Failed to delete story image {} from bucket {}: {}",
          storedValue,
          s3Properties.getBucket(),
          ex.getMessage());
    }
  }

  private boolean isManaged(String storedValue) {
    return storedValue != null && !storedValue.isBlank() && storedValue.startsWith(STORY_PREFIX);
  }

  private String resolveHost() {
    if (RequestContextHolder.getRequestAttributes() != null) {
      try {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
            .build()
            .toUriString()
            .replaceAll("/+$", "");
      } catch (IllegalStateException ignored) {
        // Fall through to configured base URL.
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
      log.error("Failed to encode story image JPEG", ex);
      throw new InvalidRequestException("Failed to process image");
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
    return (bytes[0] & 0xFF) == 0xFF
        && (bytes[1] & 0xFF) == 0xD8
        && (bytes[2] & 0xFF) == 0xFF;
  }

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
      g.setColor(Color.WHITE);
      g.fillRect(0, 0, newW, newH);
      g.drawImage(src, 0, 0, newW, newH, null);
    } finally {
      g.dispose();
    }
    return out;
  }
}
