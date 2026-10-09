package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import kz.hrms.splitupauth.config.NewsImageUploadProperties;
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

/**
 * Mirrors AvatarStorageServiceTest. Mocked S3Client so we can assert what (bucket, key,
 * content-type) hits the bucket on store / delete / read.
 */
class NewsImageStorageServiceTest {

  private NewsImageStorageService service;
  private NewsImageUploadProperties properties;
  private S3Properties s3Properties;
  private S3Client s3Client;
  private MediaImageStore mediaStore;

  @BeforeEach
  void setUp() {
    properties = new NewsImageUploadProperties();
    properties.getNewsImage().setMaxSizeBytes(500_000);
    properties.getNewsImage().setTargetWidth(256);
    properties.getNewsImage().setMaxDecodedDimension(4000);

    s3Properties = new S3Properties();
    s3Properties.setBucket("test-bucket");

    s3Client = mock(S3Client.class);
    mediaStore = new MediaImageStore(s3Client, s3Properties, 64L * 1024 * 1024, 86400, 60);

    service = new NewsImageStorageService(properties, s3Properties, s3Client, mediaStore);
    ReflectionTestUtils.setField(service, "baseUrl", "http://localhost:8080");
    ReflectionTestUtils.setField(service, "thumbWidth", 128);
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
  void store_validPng_uploadsAndReturnsNewsKey() throws Exception {
    MultipartFile file = new MockMultipartFile("file", "post.png", "image/png", makePng(200, 200));

    String key = service.store(file);

    assertTrue(key.startsWith("news/"), "key is under the news prefix");
    assertTrue(key.endsWith(".jpg"), "stored object is normalised to JPEG");

    // Two puts now: the original, plus a pre-generated card preview under news/thumb/.
    ArgumentCaptor<PutObjectRequest> req = ArgumentCaptor.forClass(PutObjectRequest.class);
    verify(s3Client, times(2)).putObject(req.capture(), any(RequestBody.class));
    PutObjectRequest original =
        req.getAllValues().stream().filter(r -> r.key().equals(key)).findFirst().orElseThrow();
    assertEquals("test-bucket", original.bucket());
    assertEquals("image/jpeg", original.contentType());
    String filename = key.substring("news/".length());
    assertTrue(
        req.getAllValues().stream().anyMatch(r -> r.key().equals("news/thumb/" + filename)),
        "a preview must be stored under news/thumb/");
  }

  @Test
  void store_validJpeg_uploadsAndReturnsNewsKey() throws Exception {
    MultipartFile file =
        new MockMultipartFile("file", "post.jpg", "image/jpeg", makeJpeg(300, 200));
    String key = service.store(file);
    assertTrue(key.startsWith("news/"));
  }

  @Test
  void store_rejectsExeBytesUnderPngExtension() {
    byte[] payload = new byte[100];
    payload[0] = 'M';
    payload[1] = 'Z';
    MultipartFile file = new MockMultipartFile("file", "trojan.png", "image/png", payload);
    assertThrows(InvalidRequestException.class, () -> service.store(file));
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void store_rejectsDisallowedExtension() {
    MultipartFile file =
        new MockMultipartFile("file", "evil.gif", "image/gif", new byte[] {0x47, 0x49, 0x46});
    assertThrows(InvalidRequestException.class, () -> service.store(file));
    verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void store_rejectsOversizeFile() throws Exception {
    properties.getNewsImage().setMaxSizeBytes(50);
    byte[] valid = makePng(120, 120);
    MultipartFile file = new MockMultipartFile("file", "big.png", "image/png", valid);
    assertThrows(InvalidRequestException.class, () -> service.store(file));
  }

  @Test
  void publicUrl_buildsBackendHostLinkForManagedKey() {
    assertEquals(
        "http://localhost:8080/api/v1/news/images/abc.jpg", service.publicUrl("news/abc.jpg"));

    ReflectionTestUtils.setField(service, "baseUrl", "https://api.ecopay.kz/");
    assertEquals(
        "https://api.ecopay.kz/api/v1/news/images/abc.jpg", service.publicUrl("news/abc.jpg"));
  }

  @Test
  void publicUrl_returnsNullForNonManagedValues() {
    assertNull(service.publicUrl(null));
    assertNull(service.publicUrl(""));
    assertNull(service.publicUrl("avatars/abc.jpg"));
    assertNull(service.publicUrl("https://example.com/some.png"));
  }

  @Test
  void loadImageBytes_returnsObjectBytesForValidFilename() {
    byte[] expected = {1, 2, 3, 4};
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), expected));

    byte[] data = service.loadImageBytes("abc.jpg");

    assertArrayEquals(expected, data);

    ArgumentCaptor<GetObjectRequest> req = ArgumentCaptor.forClass(GetObjectRequest.class);
    verify(s3Client).getObjectAsBytes(req.capture());
    assertEquals("test-bucket", req.getValue().bucket());
    assertEquals("news/abc.jpg", req.getValue().key());
  }

  @Test
  void loadImageBytes_rejectsTraversalAndBadNames() {
    assertThrows(ResourceNotFoundException.class, () -> service.loadImageBytes("../etc/passwd"));
    assertThrows(ResourceNotFoundException.class, () -> service.loadImageBytes("/absolute/leaks"));
    assertThrows(ResourceNotFoundException.class, () -> service.loadImageBytes("with spaces.jpg"));
    assertThrows(ResourceNotFoundException.class, () -> service.loadImageBytes("wrong.png"));
    verify(s3Client, never()).getObjectAsBytes(any(GetObjectRequest.class));
  }

  @Test
  void loadImageBytes_missingObjectMapsToNotFound() {
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenThrow(NoSuchKeyException.builder().message("missing").build());
    assertThrows(ResourceNotFoundException.class, () -> service.loadImageBytes("gone.jpg"));
  }

  @Test
  void deleteIfManaged_deletesManagedKey_skipsEverythingElse() {
    // Non-managed strings (avatars, null) must not touch S3.
    service.deleteIfManaged("avatars/xyz.jpg");
    service.deleteIfManaged(null);
    service.deleteIfManaged("/api/v1/news/images/old.jpg");
    verify(s3Client, never()).deleteObject(any(DeleteObjectRequest.class));

    // Deleting a managed key removes both the original and its preview.
    service.deleteIfManaged("news/abc.jpg");
    ArgumentCaptor<DeleteObjectRequest> req = ArgumentCaptor.forClass(DeleteObjectRequest.class);
    verify(s3Client, times(2)).deleteObject(req.capture());
    assertTrue(req.getAllValues().stream().allMatch(r -> r.bucket().equals("test-bucket")));
    assertTrue(req.getAllValues().stream().anyMatch(r -> r.key().equals("news/abc.jpg")));
    assertTrue(req.getAllValues().stream().anyMatch(r -> r.key().equals("news/thumb/abc.jpg")));
  }
}
