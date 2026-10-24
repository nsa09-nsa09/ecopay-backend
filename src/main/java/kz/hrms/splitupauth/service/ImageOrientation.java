package kz.hrms.splitupauth.service;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;

/**
 * B17: EXIF Orientation (1–8) → upright {@link BufferedImage}. ImageIO drops EXIF, so a JPEG from
 * an iPhone/Android camera is often decoded sideways/mirrored even though its Orientation tag said
 * which way is up. This util reads the tag with metadata-extractor and applies the inverse
 * transform before any resize. Call it with the raw bytes and the already-decoded image; a null
 * image or a tag we cannot read is a soft fallback — the original image is returned unchanged.
 */
@Slf4j
public final class ImageOrientation {

  private ImageOrientation() {}

  public static BufferedImage normalize(byte[] bytes, BufferedImage decoded) {
    if (decoded == null) return null;
    int orientation = readOrientation(bytes);
    if (orientation <= 1) return decoded; // 1 = already upright, 0/-1 = unknown
    return applyOrientation(decoded, orientation);
  }

  private static int readOrientation(byte[] bytes) {
    if (bytes == null || bytes.length == 0) return 0;
    try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
      Metadata metadata = ImageMetadataReader.readMetadata(in);
      ExifIFD0Directory dir = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
      if (dir == null) return 0;
      Integer value = dir.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
      return value == null ? 0 : value;
    } catch (ImageProcessingException | IOException | RuntimeException ex) {
      // Any failure parsing EXIF just means "fall back to no rotation" — never fail an upload
      // over a flaky header. The server still re-encodes the pixels.
      log.debug("Could not read EXIF orientation: {}", ex.getMessage());
      return 0;
    }
  }

  private static BufferedImage applyOrientation(BufferedImage src, int orientation) {
    int w = src.getWidth();
    int h = src.getHeight();
    AffineTransform t = new AffineTransform();
    int outW = w;
    int outH = h;
    switch (orientation) {
      case 2: // flip horizontal
        t.scale(-1.0, 1.0);
        t.translate(-w, 0);
        break;
      case 3: // 180°
        t.translate(w, h);
        t.rotate(Math.PI);
        break;
      case 4: // flip vertical
        t.scale(1.0, -1.0);
        t.translate(0, -h);
        break;
      case 5: // transpose: rotate 90° CW, then flip horizontal
        outW = h;
        outH = w;
        t.rotate(Math.PI / 2);
        t.scale(-1.0, 1.0);
        break;
      case 6: // rotate 90° CW
        outW = h;
        outH = w;
        t.translate(h, 0);
        t.rotate(Math.PI / 2);
        break;
      case 7: // transverse: rotate 90° CW, then flip vertical
        outW = h;
        outH = w;
        t.scale(-1.0, 1.0);
        t.translate(-h, 0);
        t.translate(h, 0);
        t.rotate(Math.PI / 2);
        break;
      case 8: // rotate 90° CCW
        outW = h;
        outH = w;
        t.translate(0, w);
        t.rotate(-Math.PI / 2);
        break;
      default:
        return src;
    }
    int type = src.getType() == 0 ? BufferedImage.TYPE_INT_RGB : src.getType();
    BufferedImage dst = new BufferedImage(outW, outH, type);
    Graphics2D g = dst.createGraphics();
    try {
      g.drawImage(src, new AffineTransformOp(t, AffineTransformOp.TYPE_BILINEAR), 0, 0);
    } finally {
      g.dispose();
    }
    return dst;
  }
}
