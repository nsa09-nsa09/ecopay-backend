package kz.hrms.splitupauth.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Drop-in replacement for {@link ImageIO#read(InputStream)} that refuses decompression bombs.
 *
 * <p>A few-KB PNG can declare 60000x60000 pixels; {@code ImageIO.read} allocates the full raster
 * before any size check runs and the JVM dies with OutOfMemoryError. This reads the declared
 * dimensions from the image header first and only decodes images within {@link #MAX_PIXELS}.
 */
public final class SafeImageDecoder {

  /** 40 megapixels (e.g. 8000x5000) — far above any upload EcoPay accepts after downscaling. */
  public static final long MAX_PIXELS = 40_000_000L;

  private SafeImageDecoder() {}

  /** Same contract as {@code ImageIO.read}: null when no reader understands the input. */
  public static BufferedImage read(InputStream in) throws IOException {
    try (ImageInputStream stream = ImageIO.createImageInputStream(in)) {
      if (stream == null) {
        return null;
      }
      Iterator<ImageReader> readers = ImageIO.getImageReaders(stream);
      if (!readers.hasNext()) {
        return null;
      }
      ImageReader reader = readers.next();
      try {
        reader.setInput(stream, true, true);
        long width = reader.getWidth(0);
        long height = reader.getHeight(0);
        if (width <= 0 || height <= 0 || width * height > MAX_PIXELS) {
          throw new IOException("Image dimensions exceed the decoding limit");
        }
        return reader.read(0);
      } finally {
        reader.dispose();
      }
    }
  }
}
