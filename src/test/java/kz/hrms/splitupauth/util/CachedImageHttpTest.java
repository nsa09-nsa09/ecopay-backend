package kz.hrms.splitupauth.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** B4: immutable cache headers, ETag and 304 handling for served images. */
class CachedImageHttpTest {

  @Test
  void invalidFilenameIs404AndNeverLoads() {
    AtomicInteger loads = new AtomicInteger();
    assertThrows(
        ResourceNotFoundException.class,
        () ->
            CachedImageHttp.serve(
                "../etc",
                false,
                null,
                () -> {
                  loads.incrementAndGet();
                  return new byte[] {1};
                }));
    assertEquals(0, loads.get());
  }

  @Test
  void okResponseCarriesImmutableHeadersAndEtag() {
    byte[] bytes = {1, 2, 3};
    ResponseEntity<byte[]> resp = CachedImageHttp.serve("abc.jpg", true, null, () -> bytes);

    assertEquals(HttpStatus.OK, resp.getStatusCode());
    assertArrayEquals(bytes, resp.getBody());
    assertEquals(MediaType.IMAGE_JPEG, resp.getHeaders().getContentType());
    assertEquals("\"abc.jpg\"", resp.getHeaders().getETag());
    String cacheControl = resp.getHeaders().getCacheControl();
    assertTrue(cacheControl.contains("max-age=31536000"), cacheControl);
    assertTrue(cacheControl.contains("public"), cacheControl);
    assertTrue(cacheControl.contains("immutable"), cacheControl);
  }

  @Test
  void matchingIfNoneMatchReturns304WithoutLoading() {
    AtomicInteger loads = new AtomicInteger();
    ResponseEntity<byte[]> resp =
        CachedImageHttp.serve(
            "abc.jpg",
            true,
            "\"abc.jpg\"",
            () -> {
              loads.incrementAndGet();
              return new byte[] {1};
            });

    assertEquals(HttpStatus.NOT_MODIFIED, resp.getStatusCode());
    assertNull(resp.getBody());
    assertEquals("\"abc.jpg\"", resp.getHeaders().getETag());
    assertEquals(0, loads.get(), "304 must not touch object storage");
  }

  @Test
  void nonMatchingIfNoneMatchStillLoads() {
    ResponseEntity<byte[]> resp =
        CachedImageHttp.serve("abc.jpg", true, "\"stale.jpg\"", () -> new byte[] {7});
    assertEquals(HttpStatus.OK, resp.getStatusCode());
    assertArrayEquals(new byte[] {7}, resp.getBody());
  }
}
