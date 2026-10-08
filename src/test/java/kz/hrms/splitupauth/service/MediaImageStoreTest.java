package kz.hrms.splitupauth.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import kz.hrms.splitupauth.config.S3Properties;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

/** B4: byte cache, lazy single-flight preview generation, and negative caching. */
class MediaImageStoreTest {

  private static final String ORIGINAL_KEY = "news/abc.jpg";
  private static final String THUMB_KEY = "news/thumb/abc.jpg";

  private S3Client s3Client;
  private MediaImageStore store;
  private byte[] originalJpeg;

  private final AtomicInteger originalReads = new AtomicInteger();
  private final AtomicInteger thumbReads = new AtomicInteger();
  private final AtomicInteger thumbPuts = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    s3Client = mock(S3Client.class);
    S3Properties props = new S3Properties();
    props.setBucket("test-bucket");
    store = new MediaImageStore(s3Client, props, 64L * 1024 * 1024, 86400, 60);
    originalJpeg = makeJpeg(800, 600);
  }

  private byte[] makeJpeg(int w, int h) throws Exception {
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
    var g = img.createGraphics();
    g.setColor(Color.BLUE);
    g.fillRect(0, 0, w, h);
    g.dispose();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ImageIO.write(img, "jpg", out);
    return out.toByteArray();
  }

  private void wireStorage(boolean thumbExists) {
    when(s3Client.getObjectAsBytes(any(GetObjectRequest.class)))
        .thenAnswer(
            inv -> {
              GetObjectRequest req = inv.getArgument(0);
              if (THUMB_KEY.equals(req.key())) {
                thumbReads.incrementAndGet();
                if (thumbExists) {
                  return ResponseBytes.fromByteArray(
                      GetObjectResponse.builder().build(), new byte[] {9, 9, 9});
                }
                throw NoSuchKeyException.builder().message("no thumb").build();
              }
              if (ORIGINAL_KEY.equals(req.key())) {
                originalReads.incrementAndGet();
                return ResponseBytes.fromByteArray(
                    GetObjectResponse.builder().build(), originalJpeg);
              }
              throw NoSuchKeyException.builder().message("missing").build();
            });
    when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
        .thenAnswer(
            inv -> {
              PutObjectRequest req = inv.getArgument(0);
              if (THUMB_KEY.equals(req.key())) {
                thumbPuts.incrementAndGet();
              }
              return PutObjectResponse.builder().build();
            });
  }

  @Test
  void loadOriginalCachesSoR2IsHitOnce() {
    wireStorage(true);
    byte[] a = store.loadOriginal(ORIGINAL_KEY);
    byte[] b = store.loadOriginal(ORIGINAL_KEY);
    assertArrayEquals(a, b);
    assertEquals(1, originalReads.get(), "second read must come from the cache");
  }

  @Test
  void missingOriginalIsNotFoundAndNegativelyCached() {
    wireStorage(false); // getObjectAsBytes for a bogus key throws NoSuchKey
    assertThrows(ResourceNotFoundException.class, () -> store.loadOriginal("news/missing.jpg"));
    assertThrows(ResourceNotFoundException.class, () -> store.loadOriginal("news/missing.jpg"));
    // Second call served from the negative cache: no new R2 read for the original key counter.
    assertEquals(0, originalReads.get());
  }

  @Test
  void thumbGeneratedOnceUnderParallelFirstRequests() throws Exception {
    wireStorage(false); // no stored thumb yet -> must be generated from the original
    int threads = 8;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    ConcurrentLinkedQueue<byte[]> results = new ConcurrentLinkedQueue<>();
    for (int i = 0; i < threads; i++) {
      pool.submit(
          () -> {
            start.await();
            results.add(store.loadThumb(THUMB_KEY, ORIGINAL_KEY, 128));
            return null;
          });
    }
    start.countDown();
    pool.shutdown();
    assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "threads finished");

    assertEquals(threads, results.size());
    // Single-flight: the original was read once and the preview stored once, no matter how many
    // parallel first requests arrived.
    assertEquals(1, originalReads.get(), "original fetched exactly once");
    assertEquals(1, thumbPuts.get(), "preview generated/stored exactly once");

    byte[] thumb = results.peek();
    assertTrue(thumb != null && thumb.length > 0);
    // Every caller got the same generated preview.
    for (byte[] r : results) {
      assertArrayEquals(thumb, r);
    }

    // Now cached: another request neither reads nor regenerates.
    store.loadThumb(THUMB_KEY, ORIGINAL_KEY, 128);
    assertEquals(1, thumbPuts.get());
  }

  @Test
  void existingThumbIsServedWithoutRegenerating() {
    wireStorage(true); // stored thumb present
    byte[] thumb = store.loadThumb(THUMB_KEY, ORIGINAL_KEY, 128);
    assertArrayEquals(new byte[] {9, 9, 9}, thumb);
    assertEquals(0, thumbPuts.get(), "existing preview must not be regenerated");
    assertEquals(0, originalReads.get(), "original not touched when the preview exists");
  }
}
