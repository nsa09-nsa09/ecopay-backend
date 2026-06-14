package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.config.AvatarUploadProperties;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AvatarStorageServiceTest {

    @TempDir Path tempDir;

    private AvatarStorageService service;
    private AvatarUploadProperties properties;

    @BeforeEach
    void setUp() {
        properties = new AvatarUploadProperties();
        properties.setDir(tempDir.toString());
        // Tight settings keep the test snappy and let us exercise size guards.
        properties.getAvatar().setMaxSizeBytes(200_000); // 200 KB cap for the test
        properties.getAvatar().setTargetSize(64);
        properties.getAvatar().setMaxDecodedDimension(4000);
        service = new AvatarStorageService(properties);
    }

    private byte[] makePng(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.GRAY);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private byte[] makeJpeg(int width, int height) throws Exception {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    @Test
    void store_validPng_persistsAndReturnsUrl() throws Exception {
        MultipartFile file = new MockMultipartFile("file", "ok.png", "image/png", makePng(80, 80));
        String url = service.store(file);
        assertTrue(url.startsWith(AvatarStorageService.AVATAR_URL_PREFIX));
        // Verify the file is actually on disk via the resolve() path traversal guard.
        String filename = url.substring(AvatarStorageService.AVATAR_URL_PREFIX.length());
        assertTrue(service.resolve(filename).isPresent());
    }

    @Test
    void store_validJpeg_persistsAndReturnsUrl() throws Exception {
        MultipartFile file = new MockMultipartFile("file", "ok.jpg", "image/jpeg", makeJpeg(120, 120));
        String url = service.store(file);
        assertTrue(url.startsWith(AvatarStorageService.AVATAR_URL_PREFIX));
    }

    @Test
    void store_rejectsExeBytesUnderPngExtension() {
        // Magic bytes "MZ" — classic EXE header. Same extension, different file.
        byte[] payload = new byte[100];
        payload[0] = 'M';
        payload[1] = 'Z';
        MultipartFile file = new MockMultipartFile("file", "trojan.png", "image/png", payload);
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.store(file));
        assertTrue(ex.getMessage().toLowerCase().contains("сигнатура")
                || ex.getMessage().toLowerCase().contains("не похож"));
    }

    @Test
    void store_rejectsDisallowedExtension() {
        MultipartFile file = new MockMultipartFile("file", "evil.gif", "image/gif", new byte[]{0x47, 0x49, 0x46});
        assertThrows(InvalidRequestException.class, () -> service.store(file));
    }

    @Test
    void store_rejectsOversizeFile() throws Exception {
        // Use a tight cap so even a small PNG counts as oversize — solid-color
        // PNGs compress too well to reliably bust the production 5 MB limit
        // inside a quick test.
        properties.getAvatar().setMaxSizeBytes(50);
        byte[] valid = makePng(80, 80);
        MultipartFile file = new MockMultipartFile("file", "big.png", "image/png", valid);
        InvalidRequestException ex = assertThrows(InvalidRequestException.class,
                () -> service.store(file));
        assertTrue(ex.getMessage().toLowerCase().contains("лимит"));
    }

    @Test
    void resolve_rejectsPathTraversal() {
        assertEquals(java.util.Optional.empty(),
                service.resolve("../etc/passwd"));
        assertEquals(java.util.Optional.empty(),
                service.resolve("/absolute/leaks"));
        assertEquals(java.util.Optional.empty(),
                service.resolve("with spaces.jpg"));
        assertEquals(java.util.Optional.empty(),
                service.resolve(UUID.randomUUID() + ".png"), "wrong extension is rejected");
    }

    @Test
    void deleteIfManaged_skipsPoolAvatarStrings() {
        // Pool IDs like "avatar-3" are not managed — must NOT touch the disk.
        service.deleteIfManaged("avatar-3");
        service.deleteIfManaged(null);
    }
}
