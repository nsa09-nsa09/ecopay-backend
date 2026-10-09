package kz.hrms.splitupauth.util;

import java.time.Duration;
import java.util.function.Supplier;
import kz.hrms.splitupauth.exception.ResourceNotFoundException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Shared HTTP serving for immutable, content-addressed media (news + stories images and their
 * previews). The object key is a random UUID and the bytes under it never change, so the response
 * is {@code Cache-Control: public, max-age=31536000, immutable} with a stable {@code ETag} equal to
 * the filename. A matching {@code If-None-Match} is answered 304 without a body and, crucially,
 * without loading the bytes (no R2 round trip).
 */
public final class CachedImageHttp {

  private CachedImageHttp() {}

  private static final CacheControl IMMUTABLE =
      CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable();

  /**
   * Builds the response for a served image. {@code loader} is invoked only when the bytes are
   * actually needed (i.e. not on a 304), so the validation and conditional-request paths never
   * touch object storage.
   */
  public static ResponseEntity<byte[]> serve(
      String filename, boolean servable, String ifNoneMatch, Supplier<byte[]> loader) {
    if (!servable) {
      throw new ResourceNotFoundException("Image not found");
    }
    String etag = "\"" + filename + "\"";
    if (etag.equals(ifNoneMatch)) {
      return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
          .cacheControl(IMMUTABLE)
          .eTag(etag)
          .build();
    }
    byte[] data = loader.get();
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_JPEG)
        .cacheControl(IMMUTABLE)
        .eTag(etag)
        .body(data);
  }
}
