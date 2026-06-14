package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.config.AvatarUploadProperties;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Handles the avatar upload pipeline end-to-end: input validation
 * (size, extension, MIME, magic bytes), decoding, downscaling, re-encoding
 * (which strips any embedded payload), and on-disk persistence under
 * {@link AvatarUploadProperties#getDir()}/avatars.
 *
 * <p>All uploaded avatars are stored as JPEG with a UUID-based filename;
 * the original filename and Content-Type from the client are never trusted.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AvatarStorageService {

    private static final String AVATARS_SUBDIR = "avatars";
    private static final Pattern ALLOWED_FILENAME =
            Pattern.compile("^[a-zA-Z0-9._-]+\\.jpg$");
    public static final String AVATAR_URL_PREFIX = "/api/v1/users/avatars/";

    private final AvatarUploadProperties properties;

    /**
     * Validates the upload and writes a 512×512 JPEG to disk. Returns the
     * public URL path stored on User.avatar — never the raw filesystem path.
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

        Path dir = Paths.get(properties.getDir(), AVATARS_SUBDIR).toAbsolutePath().normalize();
        try {
            Files.createDirectories(dir);
        } catch (IOException ex) {
            log.error("Failed to create avatars directory {}", dir, ex);
            throw new InvalidRequestException("Не удалось сохранить файл");
        }

        String filename = UUID.randomUUID().toString().replace("-", "") + ".jpg";
        Path target = dir.resolve(filename);

        try {
            Path tmp = Files.createTempFile(dir, "ava-", ".tmp");
            try {
                if (!ImageIO.write(normalized, "jpg", tmp.toFile())) {
                    throw new IOException("ImageIO returned no JPEG writer");
                }
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException io) {
                Files.deleteIfExists(tmp);
                throw io;
            }
        } catch (IOException ex) {
            log.error("Failed to write avatar to {}", target, ex);
            throw new InvalidRequestException("Не удалось сохранить файл");
        }

        return AVATAR_URL_PREFIX + filename;
    }

    /**
     * Streams a stored avatar from disk. Filename is sanitized so we never
     * resolve outside the avatars directory.
     */
    public Optional<Path> resolve(String filename) {
        if (filename == null || !ALLOWED_FILENAME.matcher(filename).matches()) {
            return Optional.empty();
        }
        Path dir = Paths.get(properties.getDir(), AVATARS_SUBDIR).toAbsolutePath().normalize();
        Path candidate = dir.resolve(filename).normalize();
        if (!candidate.startsWith(dir)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(candidate)) {
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    /**
     * Deletes a previously stored avatar. Pool avatars and external URLs
     * (anything that didn't come from us) are left untouched.
     */
    public void deleteIfManaged(String storedValue) {
        if (storedValue == null || !storedValue.startsWith(AVATAR_URL_PREFIX)) {
            return;
        }
        String filename = storedValue.substring(AVATAR_URL_PREFIX.length());
        resolve(filename).ifPresent(path -> {
            try {
                Files.deleteIfExists(path);
            } catch (IOException ex) {
                log.warn("Failed to delete avatar {}: {}", path, ex.getMessage());
            }
        });
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
