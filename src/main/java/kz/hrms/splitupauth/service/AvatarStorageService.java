package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.config.AvatarUploadProperties;
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

import javax.imageio.ImageIO;
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

/**
 * Handles the avatar upload pipeline end-to-end: input validation
 * (size, extension, MIME, magic bytes), decoding, downscaling, re-encoding
 * (which strips any embedded payload), and persistence to S3-compatible object
 * storage (Cloudflare R2 in this deployment).
 *
 * <p>Only the <b>object key</b> (e.g. {@code avatars/ab12...jpg}) is returned and
 * persisted on {@code User.avatar} — never an absolute URL. Images are served
 * <b>through the backend host</b>: {@link #publicUrl(String)} builds a
 * {@code {host}/api/v1/users/avatars/<file>} link, and the public controller
 * streams the bytes from R2 via {@link #loadAvatarBytes(String)}. R2 credentials
 * and the bucket endpoint never leave the backend. The host is taken from the
 * current request (so links auto-match localhost or the public domain with no
 * config — honouring {@code X-Forwarded-*} behind the reverse proxy), falling
 * back to {@code app.base-url} only when there is no request in scope.</p>
 *
 * <p>All uploaded avatars are stored as JPEG with a UUID-based key; the original
 * filename and Content-Type from the client are never trusted.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvatarStorageService {

    /**
     * Object-key "folder" for avatars within the bucket. This is an
     * application-structure constant, not an env knob — new media types
     * (tickets/, services/, ...) get their own prefix in their own service.
     */
    private static final String AVATAR_PREFIX = "avatars/";

    /** Public path the backend serves avatars from; pairs with {@link #AVATAR_PREFIX}. */
    public static final String AVATAR_URL_PATH = "/api/v1/users/avatars/";

    /** Filenames are UUID hex + ".jpg"; anything else can't be one of ours. */
    private static final Pattern ALLOWED_FILENAME =
            Pattern.compile("^[a-zA-Z0-9._-]+\\.jpg$");

    private final AvatarUploadProperties properties;
    private final S3Properties s3Properties;
    private final S3Client s3Client;

    /** Fallback base URL when an avatar link is built outside an HTTP request. */
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Validates the upload, normalises it to a 512×512 JPEG and stores it in the
     * bucket. Returns the object key persisted on {@code User.avatar} — never the
     * bucket URL.
     */
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidRequestException("Файл не передан");
        }
        if (file.getSize() > properties.getAvatar().getMaxSizeBytes()) {
            throw new InvalidRequestException(
                    "Файл превышает лимит "
                            + (properties.getAvatar().getMaxSizeBytes() / (1024 * 1024))
                            + " МБ");
        }

        String original = file.getOriginalFilename();
        String extension = sniffExtension(original);
        // We allowlist by extension first so a corrupted/oversized text file
        // doesn't reach the image decoder under "image/png" disguise.
        if (!isExtensionAllowed(extension)) {
            throw new InvalidRequestException(
                    "Разрешены только файлы png, jpg, jpeg");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException ex) {
            throw new InvalidRequestException("Не удалось прочитать файл");
        }

        if (!magicBytesMatch(bytes)) {
            throw new InvalidRequestException(
                    "Файл не похож на изображение (неверная сигнатура)");
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

        int maxDim = properties.getAvatar().getMaxDecodedDimension();
        if (decoded.getWidth() > maxDim || decoded.getHeight() > maxDim) {
            throw new InvalidRequestException(
                    "Изображение слишком большое (" + decoded.getWidth()
                            + "x" + decoded.getHeight() + ")");
        }

        BufferedImage normalized = downscale(decoded, properties.getAvatar().getTargetSize());
        byte[] jpeg = encodeJpeg(normalized);

        String key = AVATAR_PREFIX + UUID.randomUUID().toString().replace("-", "") + ".jpg";
        try {
            s3Client.putObject(
                    PutObjectRequest.builder()
                            .bucket(s3Properties.getBucket())
                            .key(key)
                            .contentType("image/jpeg")
                            .build(),
                    RequestBody.fromBytes(jpeg));
        } catch (S3Exception ex) {
            log.error("Failed to upload avatar to bucket {} key {}", s3Properties.getBucket(), key, ex);
            throw new InvalidRequestException("Не удалось сохранить файл в хранилище");
        }

        return key;
    }

    /**
     * Turns a stored object key into a viewable link served by this backend
     * ({@code {host}/api/v1/users/avatars/<file>.jpg}). Non-managed values
     * (null/blank/legacy) yield {@code null}.
     */
    public String publicUrl(String storedValue) {
        if (!isManaged(storedValue)) {
            return null;
        }
        String filename = storedValue.substring(AVATAR_PREFIX.length());
        return resolveHost() + AVATAR_URL_PATH + filename;
    }

    /**
     * Host the avatar link is built against. Prefers the current request's
     * scheme/host so the link auto-matches localhost or the public domain
     * (the reverse proxy's {@code X-Forwarded-*} headers are honoured via
     * {@code server.forward-headers-strategy}). Falls back to {@code app.base-url}
     * when there is no request in scope (e.g. a background job).
     */
    private String resolveHost() {
        if (RequestContextHolder.getRequestAttributes() != null) {
            try {
                return ServletUriComponentsBuilder.fromCurrentContextPath()
                        .build().toUriString().replaceAll("/+$", "");
            } catch (IllegalStateException ignored) {
                // No usable request — fall through to the configured base URL.
            }
        }
        return (baseUrl == null ? "" : baseUrl).replaceAll("/+$", "");
    }

    /**
     * Streams a stored avatar's bytes from the bucket for the public serving
     * endpoint. The filename is validated so it can only resolve to a key under
     * our avatar prefix. Throws {@link ResourceNotFoundException} when missing.
     */
    public byte[] loadAvatarBytes(String filename) {
        if (filename == null || !ALLOWED_FILENAME.matcher(filename).matches()) {
            throw new ResourceNotFoundException("Avatar not found");
        }
        String key = AVATAR_PREFIX + filename;
        try {
            return s3Client.getObjectAsBytes(GetObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(key)
                    .build()).asByteArray();
        } catch (NoSuchKeyException ex) {
            throw new ResourceNotFoundException("Avatar not found");
        } catch (S3Exception ex) {
            log.warn("Failed to read avatar {} from bucket {}: {}",
                    key, s3Properties.getBucket(), ex.getMessage());
            throw new ResourceNotFoundException("Avatar not found");
        }
    }

    /**
     * Deletes a previously stored avatar from the bucket. Non-managed values
     * (null, or leftover legacy strings) are ignored.
     */
    public void deleteIfManaged(String storedValue) {
        if (!isManaged(storedValue)) {
            return;
        }
        try {
            s3Client.deleteObject(DeleteObjectRequest.builder()
                    .bucket(s3Properties.getBucket())
                    .key(storedValue)
                    .build());
        } catch (S3Exception ex) {
            log.warn("Failed to delete avatar {} from bucket {}: {}",
                    storedValue, s3Properties.getBucket(), ex.getMessage());
        }
    }

    /** A value we own is one stored under our avatar key prefix. */
    private boolean isManaged(String storedValue) {
        return storedValue != null
                && !storedValue.isBlank()
                && storedValue.startsWith(AVATAR_PREFIX);
    }

    private byte[] encodeJpeg(BufferedImage image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "jpg", out)) {
                throw new IOException("ImageIO returned no JPEG writer");
            }
        } catch (IOException ex) {
            log.error("Failed to encode avatar JPEG", ex);
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
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if ((bytes[0] & 0xFF) == 0x89
                && (bytes[1] & 0xFF) == 0x50
                && (bytes[2] & 0xFF) == 0x4E
                && (bytes[3] & 0xFF) == 0x47) {
            return true;
        }
        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF
                && (bytes[1] & 0xFF) == 0xD8
                && (bytes[2] & 0xFF) == 0xFF) {
            return true;
        }
        return false;
    }

    private BufferedImage downscale(BufferedImage src, int targetSize) {
        int width = src.getWidth();
        int height = src.getHeight();
        double scale = Math.min(1.0, (double) targetSize / Math.max(width, height));
        int newW = Math.max(1, (int) Math.round(width * scale));
        int newH = Math.max(1, (int) Math.round(height * scale));

        BufferedImage out = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING,
                    RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
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
