package kz.hrms.splitupauth.service;

import kz.hrms.splitupauth.config.AvatarUploadProperties;
import kz.hrms.splitupauth.config.S3Properties;
import kz.hrms.splitupauth.exception.InvalidRequestException;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvatarStorageServiceTest {

    private AvatarStorageService service;
    private AvatarUploadProperties properties;
    private S3Properties s3Properties;
    private S3Client s3Client;

    @BeforeEach
    void setUp() {
        properties = new AvatarUploadProperties();
        // Tight settings keep the test snappy and let us exercise size guards.
        properties.getAvatar().setMaxSizeBytes(200_000); // 200 KB cap for the test
        properties.getAvatar().setTargetSize(64);
        properties.getAvatar().setMaxDecodedDimension(4000);

        s3Properties = new S3Properties();
        s3Properties.setBucket("test-bucket");

        s3Client = mock(S3Client.class);

        service = new AvatarStorageService(properties, s3Properties, s3Client);
        ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
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
    void store_validPng_uploadsAndReturnsObjectKey() throws Exception {
        MultipartFile file = new MockMultipartFile("file", "ok.png", "image/png", makePng(80, 80));

        String key = service.store(file);

        assertTrue(key.startsWith("avatars/"), "key is under the avatar prefix");
        assertTrue(key.endsWith(".jpg"), "stored object is normalised to JPEG");

        // The uploaded object lands in the configured bucket under the returned key.
        ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(req.capture(), any(RequestBody.class));
        assertEquals("test-bucket", req.getValue().bucket());
        assertEquals(key, req.getValue().key());
        assertEquals("image/jpeg", req.getValue().contentType());
    }

    @Test
    void store_validJpeg_uploadsAndReturnsObjectKey() throws Exception {
        MultipartFile file = new MockMultipartFile("file", "ok.jpg", "image/jpeg", makeJpeg(120, 120));
        String key = service.store(file);
        assertTrue(key.startsWith("avatars/"));
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
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    void store_rejectsDisallowedExtension() {
        MultipartFile file = new MockMultipartFile("file", "evil.gif", "image/gif", new byte[]{0x47, 0x49, 0x46});
        assertThrows(InvalidRequestException.class, () -> service.store(file));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
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
    void publicUrl_buildsBackendHostLinkForManagedKey() {
        assertEquals("http://localhost:8080/api/v1/users/avatars/abc.jpg",
                service.publicUrl("avatars/abc.jpg"));

        // A trailing slash on the base URL must not double up.
        ReflectionTestUtils.setField(service, "baseUrl", "https://api.ecopay.kz/");
        assertEquals("https://api.ecopay.kz/api/v1/users/avatars/abc.jpg",
                service.publicUrl("avatars/abc.jpg"));
    }

    @Test
    void publicUrl_returnsNullForNonManagedValues() {
        // Legacy local paths / pasted public URLs / null are not resolvable keys.
        assertNull(service.publicUrl(null));
        assertNull(service.publicUrl(""));
        assertNull(service.publicUrl("/api/v1/users/avatars/old.jpg"));
        assertNull(service.publicUrl("https://example.com/some.png"));
    }

    @Test
    void loadAvatarBytes_returnsObjectBytesForValidFilename() {
        byte[] expected = {1, 2, 3, 4};
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), expected));

        byte[] data = service.loadAvatarBytes("abc.jpg");

        assertArrayEquals(expected, data);

        ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObjectAsBytes(req.capture());
        assertEquals("test-bucket", req.getValue().bucket());
        assertEquals("avatars/abc.jpg", req.getValue().key());
    }

    @Test
    void loadAvatarBytes_rejectsTraversalAndBadNames() {
        assertThrows(ResourceNotFoundException.class, () -> service.loadAvatarBytes("../etc/passwd"));
        assertThrows(ResourceNotFoundException.class, () -> service.loadAvatarBytes("/absolute/leaks"));
        assertThrows(ResourceNotFoundException.class, () -> service.loadAvatarBytes("with spaces.jpg"));
        assertThrows(ResourceNotFoundException.class, () -> service.loadAvatarBytes("wrong.png"));
        verify(s3Client, never()).getObjectAsBytes(any(GetObjectRequest.class));
    }

    @Test
    void loadAvatarBytes_missingObjectMapsToNotFound() {
        when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());
        assertThrows(ResourceNotFoundException.class, () -> service.loadAvatarBytes("gone.jpg"));
    }

    @Test
    void deleteIfManaged_deletesManagedKey_skipsEverythingElse() {
        // Non-managed strings (pool ids, legacy paths, null) must not touch S3.
        service.deleteIfManaged("avatar-3");
        service.deleteIfManaged(null);
        service.deleteIfManaged("/api/v1/users/avatars/old.jpg");
        verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

        // A managed key is deleted from the configured bucket.
        service.deleteIfManaged("avatars/abc.jpg");
        ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(req.capture());
        assertEquals("test-bucket", req.getValue().bucket());
        assertEquals("avatars/abc.jpg", req.getValue().key());
    }
}
